package co.ecomers.api.dto.backoffice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PedidoPendienteResponse(
        long idPedido,
        String idPublico,
        long idCliente,
        String cliente,
        String email,
        BigDecimal total,
        OffsetDateTime fechaCreacion,
        OffsetDateTime fechaExpiracion,
        Long minutosRestantes) { }
