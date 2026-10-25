package co.ecomers.api.model;

import java.math.BigDecimal;

/**
 * Notificacion de la pasarela ya verificada y traducida, lista para PKG_PAGO.registrar_notificacion.
 * {@code estado} es APROBADO, RECHAZADO, PENDIENTE o ANULADO. {@code idEvento} identifica la entrega:
 * si llega otra vez, la base la trata como la misma.
 */
public record NotificacionPago(
        String pasarela,
        String referencia,
        String idTransaccion,
        String idEvento,
        String estado,
        BigDecimal monto,
        String metodo,
        String payload) { }
