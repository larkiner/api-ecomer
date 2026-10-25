package co.ecomers.api.service.integracion;

import co.ecomers.api.model.EventoCola;
import co.ecomers.api.repository.integracion.ColaRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ciclo de un consumidor de la cola: toma un lote y ejecuta cada evento; si uno falla lo reporta
 * con {@code fallar} (la base lo reintenta con espera creciente) y sigue con el siguiente. La
 * entrega es "al menos una vez": el trabajo de cada evento debe tolerar repetirse.
 */
@Service
public class ProcesadorColaService {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorColaService.class);

    private final ColaRepository cola;

    public ProcesadorColaService(ColaRepository cola) {
        this.cola = cola;
    }

    /** Devuelve cuantos eventos tomo. */
    public int procesar(String tipo, int limite, ManejadorEvento manejador) {
        if (EventoCola.PAGO_NOTIFICADO.equals(tipo)) {
            throw new IllegalArgumentException("PAGO_NOTIFICADO lo consume el JOB de la base, no el backend");
        }
        List<EventoCola> lote = cola.tomarLote(tipo, limite);
        for (EventoCola evento : lote) {
            try {
                manejador.manejar(evento);
            } catch (Exception e) {
                log.warn("Evento {} ({}) fallo: {}", evento.id(), tipo, e.toString());
                reportarFallo(evento, tipo, e);
            }
        }
        return lote.size();
    }

    private void reportarFallo(EventoCola evento, String tipo, Exception causa) {
        try {
            cola.fallar(evento.id(), tipo, causa.getClass().getSimpleName() + ": " + causa.getMessage());
        } catch (RuntimeException e) {
            // Si tampoco se puede reportar, el JOB de la base rescata el evento a los 10 minutos.
            log.error("No se pudo reportar el fallo del evento {}: {}", evento.id(), e.toString());
        }
    }

    /** Trabajo de un evento. Es quien llama a completar / completarErp cuando termina bien. */
    @FunctionalInterface
    public interface ManejadorEvento {
        void manejar(EventoCola evento) throws Exception;
    }
}
