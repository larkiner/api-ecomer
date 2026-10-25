package co.ecomers.api.client.wompi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WompiTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode leer(String s) {
        return json.readTree(s);
    }

    // ---------------- Firma de integridad y checkout ----------------

    @Test
    void firmaDeIntegridadEsSha256DeLaConcatenacion() {
        // printf '%s' REF 23800000 COP test_integrity_secreto | sha256sum
        assertThat(FirmaIntegridad.calcular("A1B2C3D4E5F60718293A4B5C6D7E8F90", 23_800_000L, "COP", "test_integrity_secreto"))
                .isEqualTo("e355cc35372af7fc91cf42f613f1695102cf17219dd3a90f0dad1d02aa54bd27");
    }

    @Test
    void urlDelCheckoutLlevaLosParametrosCodificados() {
        var props = new WompiProperties("pub_test_x", "s", "", null, "http://localhost:5173/pedidos/{idPedido}/resultado");
        String url = CheckoutWompi.armarUrl(props, "A1B2C3D4E5F60718293A4B5C6D7E8F90", 23_800_000L, "firma123",
                props.redirectPara(10L));

        assertThat(url).startsWith("https://checkout.wompi.co/p/?public-key=pub_test_x")
                .contains("&currency=COP")
                .contains("&amount-in-cents=23800000")
                .contains("&reference=A1B2C3D4E5F60718293A4B5C6D7E8F90")
                .contains("&signature%3Aintegrity=firma123")
                .contains("&redirect-url=http%3A%2F%2Flocalhost%3A5173%2Fpedidos%2F10%2Fresultado");
    }

    @Test
    void propiedadesNoImprimenSecretos() {
        var props = new WompiProperties("pub", "SECRETO_INTEGRIDAD", "SECRETO_EVENTOS", null, null);
        assertThat(props.toString()).doesNotContain("SECRETO_INTEGRIDAD").doesNotContain("SECRETO_EVENTOS");
        assertThat(props.checkoutConfigurado()).isTrue();
        assertThat(new WompiProperties("", "", "", null, null).checkoutConfigurado()).isFalse();
    }

    // ---------------- Firma de eventos ----------------

    @Test
    void firmaDeEventoValida() {
        assertThat(VerificadorFirmaWompi.esValida(leer(EventosWompi.aprobado()), EventosWompi.SECRETO)).isTrue();
    }

    @Test
    void montoOEstadoAlteradosInvalidanLaFirma() {
        assertThat(VerificadorFirmaWompi.esValida(
                leer(EventosWompi.transaccion("APPROVED", 100L, EventosWompi.CHECKSUM_APROBADO)), EventosWompi.SECRETO)).isFalse();
        assertThat(VerificadorFirmaWompi.esValida(
                leer(EventosWompi.transaccion("DECLINED", 23_800_000L, EventosWompi.CHECKSUM_APROBADO)), EventosWompi.SECRETO))
                .isFalse();
    }

    @Test
    void secretoDistintoOVacioInvalidaLaFirma() {
        assertThat(VerificadorFirmaWompi.esValida(leer(EventosWompi.aprobado()), "otro")).isFalse();
        assertThat(VerificadorFirmaWompi.esValida(leer(EventosWompi.aprobado()), "")).isFalse();
    }

    @Test
    void propiedadFaltanteOSinFirmaEsInvalido() {
        String sinMonto = """
                {"event":"transaction.updated","data":{"transaction":{"id":"1234-1610641025-49201","status":"APPROVED"}},
                "signature":{"properties":["transaction.id","transaction.status","transaction.amount_in_cents"],
                "checksum":"%s"},"timestamp":1530291411}""".formatted(EventosWompi.CHECKSUM_APROBADO);
        assertThat(VerificadorFirmaWompi.esValida(leer(sinMonto), EventosWompi.SECRETO)).isFalse();
        assertThat(VerificadorFirmaWompi.esValida(leer("{\"event\":\"x\",\"data\":{}}"), EventosWompi.SECRETO)).isFalse();
    }

    @Test
    void checksumEnMayusculasTambienCoincide() {
        String mayus = EventosWompi.transaccion("APPROVED", 23_800_000L, EventosWompi.CHECKSUM_APROBADO.toUpperCase());
        assertThat(VerificadorFirmaWompi.esValida(leer(mayus), EventosWompi.SECRETO)).isTrue();
    }

    // ---------------- Lectura del evento ----------------

    @Test
    void eventoSeLeeYSuEstadoSeTraduce() {
        var e = EventoWompi.desde(leer(EventosWompi.aprobado()));
        assertThat(e.esTransaccionActualizada()).isTrue();
        assertThat(e.referencia()).isEqualTo("A1B2C3D4E5F60718293A4B5C6D7E8F90");
        assertThat(e.idTransaccion()).isEqualTo("1234-1610641025-49201");
        assertThat(e.centavos()).isEqualTo(23_800_000L);
        assertThat(e.metodo()).isEqualTo("PSE");
        assertThat(e.checksum()).isEqualTo(EventosWompi.CHECKSUM_APROBADO);
        assertThat(e.estadoTraducido()).contains("APROBADO");
    }

    @Test
    void traduccionDeEstados() {
        assertThat(EventoWompi.traducir("APPROVED")).contains("APROBADO");
        assertThat(EventoWompi.traducir("DECLINED")).contains("RECHAZADO");
        assertThat(EventoWompi.traducir("ERROR")).contains("RECHAZADO");
        assertThat(EventoWompi.traducir("VOIDED")).contains("ANULADO");
        assertThat(EventoWompi.traducir("PENDING")).contains("PENDIENTE");
        assertThat(EventoWompi.traducir("FOO")).isEmpty();
        assertThat(EventoWompi.traducir(null)).isEmpty();
    }
}
