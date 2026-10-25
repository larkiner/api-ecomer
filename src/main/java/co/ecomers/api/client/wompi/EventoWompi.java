package co.ecomers.api.client.wompi;

import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Lectura de un evento de Wompi ({@code transaction.updated}). Aisla el formato externo: el resto
 * de la aplicacion solo ve estos campos y el estado ya traducido al de PKG_PAGO.
 */
public record EventoWompi(
        String tipo,
        String idTransaccion,
        String referencia,
        String estadoWompi,
        long centavos,
        String metodo,
        String checksum) {

    public static final String PASARELA = "WOMPI";
    public static final String TRANSACCION_ACTUALIZADA = "transaction.updated";

    private static final Map<String, String> ESTADOS = Map.of(
            "APPROVED", "APROBADO",
            "DECLINED", "RECHAZADO",
            "ERROR", "RECHAZADO",
            "VOIDED", "ANULADO",
            "PENDING", "PENDIENTE");

    public static EventoWompi desde(JsonNode evento) {
        JsonNode tx = evento.path("data").path("transaction");
        return new EventoWompi(
                evento.path("event").asString(""),
                tx.path("id").asString(null),
                tx.path("reference").asString("").toUpperCase(),
                tx.path("status").asString(null),
                tx.path("amount_in_cents").asLong(0),
                tx.path("payment_method_type").asString(null),
                evento.path("signature").path("checksum").asString(null));
    }

    public boolean esTransaccionActualizada() {
        return TRANSACCION_ACTUALIZADA.equals(tipo);
    }

    /** APROBADO / RECHAZADO / ANULADO / PENDIENTE, o vacio si Wompi manda un estado desconocido. */
    public Optional<String> estadoTraducido() {
        return traducir(estadoWompi);
    }

    public static Optional<String> traducir(String estadoWompi) {
        return Optional.ofNullable(estadoWompi).map(ESTADOS::get);
    }
}
