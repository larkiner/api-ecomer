package co.ecomers.api.service.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.client.wompi.EventosWompi;
import co.ecomers.api.client.wompi.WompiProperties;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.NotificacionPago;
import co.ecomers.api.repository.integracion.NotificacionPagoRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class NotificacionPagoServiceTest {

    private final NotificacionPagoRepository repo = mock(NotificacionPagoRepository.class);

    /** Los eventos de ejemplo de Wompi traen timestamp 1530291411 (julio de 2018): el reloj de la prueba va un minuto despues. */
    private static final Instant EMITIDO = Instant.ofEpochSecond(1530291411L);

    private NotificacionPagoService servicio(String secreto, Instant ahora) {
        var props = new WompiProperties("pub", "int", secreto, null, null);
        return new NotificacionPagoService(repo, props, JsonMapper.builder().build(), Clock.fixed(ahora, ZoneOffset.UTC));
    }

    private NotificacionPagoService servicio(String secreto) {
        return servicio(secreto, EMITIDO.plusSeconds(60));
    }

    @Test
    void eventoFirmadoPeroViejoSeIgnoraSinTocarLaBase() {
        var tarde = servicio(EventosWompi.SECRETO, EMITIDO.plus(NotificacionPagoService.ANTIGUEDAD_MAXIMA).plusSeconds(1));
        assertThat(tarde.procesar(EventosWompi.aprobado()).tipo()).isEqualTo(ResultadoWebhook.Tipo.IGNORADA);
        verifyNoInteractions(repo);
    }

    @Test
    void eventoQueDiceVenirDelFuturoSeIgnora() {
        var antes = servicio(EventosWompi.SECRETO, EMITIDO.minus(NotificacionPagoService.DESFASE_MAXIMO).minusSeconds(1));
        assertThat(antes.procesar(EventosWompi.aprobado()).tipo()).isEqualTo(ResultadoWebhook.Tipo.IGNORADA);
        verifyNoInteractions(repo);
    }

    @Test
    void reintentoDentroDeLaVentanaSigueValiendo() {
        when(repo.registrar(any())).thenReturn(5L);
        var reintento = servicio(EventosWompi.SECRETO, EMITIDO.plusSeconds(6 * 3600));
        assertThat(reintento.procesar(EventosWompi.aprobado()).tipo()).isEqualTo(ResultadoWebhook.Tipo.REGISTRADA);
    }

    private final NotificacionPagoService servicio = servicio(EventosWompi.SECRETO);

    @Test
    void aprobadoSeRegistraConElEstadoTraducidoYElMontoEnPesos() {
        when(repo.registrar(any())).thenReturn(77L);

        var r = servicio.procesar(EventosWompi.aprobado());

        assertThat(r.tipo()).isEqualTo(ResultadoWebhook.Tipo.REGISTRADA);
        assertThat(r.idPagoEvento()).isEqualTo(77L);
        var captor = ArgumentCaptor.forClass(NotificacionPago.class);
        verify(repo).registrar(captor.capture());
        NotificacionPago n = captor.getValue();
        assertThat(n.pasarela()).isEqualTo("WOMPI");
        assertThat(n.referencia()).isEqualTo(EventosWompi.REFERENCIA.toUpperCase());
        assertThat(n.idTransaccion()).isEqualTo("1234-1610641025-49201");
        assertThat(n.idEvento()).isEqualTo(EventosWompi.CHECKSUM_APROBADO);
        assertThat(n.estado()).isEqualTo("APROBADO");
        assertThat(n.monto()).isEqualByComparingTo(new BigDecimal("238000.00"));
        assertThat(n.metodo()).isEqualTo("PSE");
        assertThat(n.payload()).isEqualTo(EventosWompi.aprobado());
    }

    @Test
    void firmaInvalidaNoTocaLaBase() {
        var r = servicio.procesar(EventosWompi.transaccion("APPROVED", 100L, EventosWompi.CHECKSUM_APROBADO));
        assertThat(r.tipo()).isEqualTo(ResultadoWebhook.Tipo.FIRMA_INVALIDA);
        verifyNoInteractions(repo);
    }

    @Test
    void jsonInvalido() {
        assertThat(servicio.procesar("esto no es json").tipo()).isEqualTo(ResultadoWebhook.Tipo.JSON_INVALIDO);
        assertThat(servicio.procesar("[1,2]").tipo()).isEqualTo(ResultadoWebhook.Tipo.JSON_INVALIDO);
        verifyNoInteractions(repo);
    }

    @Test
    void sinSecretoDeEventosSeRechazaTodo() {
        assertThat(servicio("").procesar(EventosWompi.aprobado()).tipo()).isEqualTo(ResultadoWebhook.Tipo.NO_CONFIGURADO);
        verifyNoInteractions(repo);
    }

    @Test
    void otroTipoDeEventoOEstadoDesconocidoSeIgnoran() {
        assertThat(servicio.procesar(EventosWompi.otroEvento()).tipo()).isEqualTo(ResultadoWebhook.Tipo.IGNORADA);
        assertThat(servicio.procesar(EventosWompi.transaccion("FOO", 23_800_000L, EventosWompi.CHECKSUM_ESTADO_FOO)).tipo())
                .isEqualTo(ResultadoWebhook.Tipo.IGNORADA);
        verifyNoInteractions(repo);
    }

    @Test
    void referenciaDesconocidaOConflictoSeIgnoranParaQueWompiNoReintente() {
        when(repo.registrar(any())).thenThrow(new NegocioException(CodigoError.PAGO_NO_ENCONTRADO, "no existe"));
        assertThat(servicio.procesar(EventosWompi.aprobado()).tipo()).isEqualTo(ResultadoWebhook.Tipo.IGNORADA);

        org.mockito.Mockito.doThrow(new NegocioException(CodigoError.REFERENCIA_EXTERNA_EN_CONFLICTO, "conflicto"))
                .when(repo).registrar(any());
        assertThat(servicio.procesar(EventosWompi.aprobado()).tipo()).isEqualTo(ResultadoWebhook.Tipo.IGNORADA);
    }

    @Test
    void baseOcupadaSePropagaParaQueWompiReintente() {
        when(repo.registrar(any())).thenThrow(new NegocioException(CodigoError.RECURSO_OCUPADO, "ocupado", 20188));
        assertThatThrownBy(() -> servicio.procesar(EventosWompi.aprobado()))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.RECURSO_OCUPADO);
    }
}
