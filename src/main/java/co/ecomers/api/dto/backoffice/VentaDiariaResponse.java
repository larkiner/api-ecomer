package co.ecomers.api.dto.backoffice;

import java.math.BigDecimal;
import java.time.LocalDate;

public record VentaDiariaResponse(
        LocalDate fecha,
        long pedidosPagados,
        BigDecimal subtotal,
        BigDecimal impuesto,
        BigDecimal total) { }
