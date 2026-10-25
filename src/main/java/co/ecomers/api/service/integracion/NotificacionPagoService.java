package co.ecomers.api.service.integracion;

import co.ecomers.api.client.wompi.EventoWompi;
import co.ecomers.api.client.wompi.VerificadorFirmaWompi;
import co.ecomers.api.client.wompi.WompiProperties;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.NotificacionPago;
import co.ecomers.api.repository.integracion.NotificacionPagoRepository;
import co.ecomers.api.service.integracion.ResultadoWebhook.Tipo;
import co.ecomers.api.util.Dinero;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook de Wompi: verifica la firma, traduce el estado y registra la notificacion, sin esperar a
 * aplicarla (eso lo hace el JOB de la base). Si la misma notificacion llega dos veces, la base la
 * trata como una sola.
 */
@Service
public class NotificacionPagoService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionPagoService.class);

    /**
     * Antiguedad maxima de un evento firmado. Wompi reintenta durante varias horas, asi que la ventana es amplia: el
     * objetivo es rechazar la repeticion de un evento viejo capturado, no los reintentos legitimos.
     */
    static final Duration ANTIGUEDAD_MAXIMA = Duration.ofHours(72);
    /** Tolerancia por relojes desfasados para eventos que dicen venir del futuro. */
    static final Duration DESFASE_MAXIMO = Duration.ofMinutes(5);

    private final NotificacionPagoRepository repo;
    private final WompiProperties wompi;
    private final ObjectMapper json;
    private final Clock reloj;

    @Autowired
    public NotificacionPagoService(NotificacionPagoRepository repo, WompiProperties wompi, ObjectMapper json) {
        this(repo, wompi, json, Clock.systemUTC());
    }

    /** Para pruebas: un reloj fijo. */
    NotificacionPagoService(NotificacionPagoRepository repo, WompiProperties wompi, ObjectMapper json, Clock reloj) {
        this.repo = repo;
        this.wompi = wompi;
        this.json = json;
        this.reloj = reloj;
    }

    public ResultadoWebhook procesar(String cuerpo) {
        if (!wompi.eventosConfigurados()) {
            log.error("Llego un webhook de Wompi pero falta WOMPI_EVENTS_SECRET: se rechaza");
            return ResultadoWebhook.de(Tipo.NO_CONFIGURADO);
        }
        JsonNode nodo;
        try {
            nodo = json.readTree(cuerpo);
        } catch (JacksonException e) {
            return ResultadoWebhook.de(Tipo.JSON_INVALIDO);
        }
        if (nodo == null || !nodo.isObject()) {
            return ResultadoWebhook.de(Tipo.JSON_INVALIDO);
        }
        if (!VerificadorFirmaWompi.esValida(nodo, wompi.eventsSecret())) {
            log.warn("Webhook de Wompi con firma invalida");
            return ResultadoWebhook.de(Tipo.FIRMA_INVALIDA);
        }

        if (!esVigente(nodo)) {
            log.warn("Webhook de Wompi fuera de la ventana de tiempo permitida: se ignora");
            return ResultadoWebhook.de(Tipo.IGNORADA);
        }
        EventoWompi evento = EventoWompi.desde(nodo);
        if (!evento.esTransaccionActualizada()) {
            return ResultadoWebhook.de(Tipo.IGNORADA);
        }
        Optional<String> estado = evento.estadoTraducido();
        if (estado.isEmpty()) {
            log.warn("Webhook de Wompi con estado desconocido: {}", evento.estadoWompi());
            return ResultadoWebhook.de(Tipo.IGNORADA);
        }

        var notificacion = new NotificacionPago(EventoWompi.PASARELA, evento.referencia(), evento.idTransaccion(),
                evento.checksum(), estado.get(), Dinero.deCentavos(evento.centavos()), evento.metodo(), cuerpo);
        try {
            return ResultadoWebhook.registrada(repo.registrar(notificacion));
        } catch (NegocioException e) {
            switch (e.codigo()) {
                case PAGO_NO_ENCONTRADO -> {
                    log.warn("Webhook de Wompi para una referencia desconocida: {}", notificacion.referencia());
                    return ResultadoWebhook.de(Tipo.IGNORADA);
                }
                case REFERENCIA_EXTERNA_EN_CONFLICTO -> {
                    log.error("Conflicto de referencia externa para el pago {} (transaccion {}): revisar a mano",
                            notificacion.referencia(), notificacion.idTransaccion());
                    return ResultadoWebhook.de(Tipo.IGNORADA);
                }
                default -> throw e;
            }
        }
    }

    /** El {@code timestamp} del evento (segundos Unix, firmado) debe ser reciente y no estar en el futuro. */
    boolean esVigente(JsonNode evento) {
        JsonNode ts = evento.path("timestamp");
        if (!ts.isIntegralNumber()) {
            return false;
        }
        Instant emitido = Instant.ofEpochSecond(ts.asLong());
        Instant ahora = reloj.instant();
        return !emitido.isBefore(ahora.minus(ANTIGUEDAD_MAXIMA)) && !emitido.isAfter(ahora.plus(DESFASE_MAXIMO));
    }
}
