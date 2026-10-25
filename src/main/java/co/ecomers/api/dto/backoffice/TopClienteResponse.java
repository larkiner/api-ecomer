package co.ecomers.api.dto.backoffice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TopClienteResponse(
        long idCliente,
        String nombre,
        String email,
        long pedidosPagados,
        BigDecimal totalComprado,
        OffsetDateTime ultimaCompra) { }
