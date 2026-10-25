package co.ecomers.api.dto.backoffice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Pedido y pago que no cuadran (por ejemplo, un pago aprobado de un pedido expirado: hay que devolver el dinero). */
public record ConciliacionPagoResponse(
        long idPedido,
        String idPublicoPedido,
        String estadoPedido,
        BigDecimal totalPedido,
        Long idPago,
        BigDecimal montoPago,
        OffsetDateTime fechaAprobacion,
        String inconsistencia) { }
