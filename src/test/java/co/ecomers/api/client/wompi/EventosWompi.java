package co.ecomers.api.client.wompi;

/**
 * Eventos de ejemplo de Wompi con su checksum calculado aparte (secreto de eventos "test_events_x"):
 * printf '%s' ID STATUS MONTO 1530291411 test_events_x | sha256sum
 */
public final class EventosWompi {

    public static final String SECRETO = "test_events_x";
    public static final String REFERENCIA = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
    public static final String CHECKSUM_APROBADO = "f17d4fcfae302ef2ad75eb539023ead296ee11e9f240d92c16adc162673fc797";
    public static final String CHECKSUM_ESTADO_FOO = "405299ffc3f497acce7c12da4cc2f78bac207e47724134faa6be81ae8ac5538d";
    public static final String CHECKSUM_OTRO_EVENTO = "b1741ef5903b92808aee191f178bd74982c6902ccb712b905b1bc01617fc04cc";

    private EventosWompi() { }

    public static String transaccion(String estado, long centavos, String checksum) {
        return """
                {"event":"transaction.updated","data":{"transaction":{"id":"1234-1610641025-49201",
                "amount_in_cents":%d,"reference":"%s","currency":"COP","payment_method_type":"PSE",
                "status":"%s"}},"environment":"test","signature":{"properties":
                ["transaction.id","transaction.status","transaction.amount_in_cents"],"checksum":"%s"},
                "timestamp":1530291411,"sent_at":"2018-07-20T16:45:05.000Z"}
                """.formatted(centavos, REFERENCIA, estado, checksum);
    }

    public static String aprobado() {
        return transaccion("APPROVED", 23_800_000L, CHECKSUM_APROBADO);
    }

    /** Evento de otro tipo con firma valida: se ignora. */
    public static String otroEvento() {
        return """
                {"event":"nequi_token.updated","data":{"transaction":{"id":"tok-1"}},"environment":"test",
                "signature":{"properties":["transaction.id"],"checksum":"%s"},"timestamp":1530291411}
                """.formatted(CHECKSUM_OTRO_EVENTO);
    }
}
