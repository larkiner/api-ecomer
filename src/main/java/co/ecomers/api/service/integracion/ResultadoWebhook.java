package co.ecomers.api.service.integracion;

/** Resultado de procesar una notificacion de la pasarela; la capa web lo traduce a HTTP. */
public record ResultadoWebhook(Tipo tipo, Long idPagoEvento) {

    public enum Tipo {
        /** Guardada y encolada para el JOB de la base. */
        REGISTRADA,
        /** Sin efecto y sin arreglo posible: se responde 200 para que la pasarela no reintente. */
        IGNORADA,
        JSON_INVALIDO,
        FIRMA_INVALIDA,
        /** Falta el secreto de eventos: se rechaza todo (la pasarela reintentara cuando se configure). */
        NO_CONFIGURADO
    }

    static ResultadoWebhook registrada(long idPagoEvento) {
        return new ResultadoWebhook(Tipo.REGISTRADA, idPagoEvento);
    }

    static ResultadoWebhook de(Tipo tipo) {
        return new ResultadoWebhook(tipo, null);
    }
}
