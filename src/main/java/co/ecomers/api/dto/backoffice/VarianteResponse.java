package co.ecomers.api.dto.backoffice;

import java.math.BigDecimal;

/** Una variante de un producto en el listado del backoffice (incluye las inactivas). El precio viaja como texto. */
public record VarianteResponse(
        long idVariante,
        String sku,
        String nombre,
        BigDecimal precioBase,
        boolean controlaStock,
        boolean activa) { }
