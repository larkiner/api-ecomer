package co.ecomers.api.model;

/** Evento de COLA_EVENTO tomado por un consumidor (queda en PROCESANDO hasta completarlo o fallarlo). */
public record EventoCola(long id, String tipo, String clave, String payload, int intentos) {

    public static final String ENVIAR_CORREO = "ENVIAR_CORREO";
    public static final String SINCRONIZAR_ERP = "SINCRONIZAR_ERP";
    /** Lo consume el JOB de la base; el backend nunca lo toma. */
    public static final String PAGO_NOTIFICADO = "PAGO_NOTIFICADO";
}
