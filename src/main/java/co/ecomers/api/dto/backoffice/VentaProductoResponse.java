package co.ecomers.api.dto.backoffice;

import java.math.BigDecimal;
import java.time.LocalDate;

public record VentaProductoResponse(
        LocalDate fecha,
        long idVariante,
        String sku,
        String nombre,
        long unidades,
        BigDecimal subtotal,
        BigDecimal impuesto,
        BigDecimal total) { }
