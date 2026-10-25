package co.ecomers.api.dto.tienda;

import java.math.BigDecimal;

/**
 * Variante vendible con su precio vigente. Los precios son orientativos: el definitivo lo congela el pedido.
 * {@code imagenUrl} es la foto del producto (la misma para todas sus variantes) o {@code null} si no tiene.
 */
public record VarianteResponse(
        long idVariante,
        String sku,
        String nombre,
        boolean controlaStock,
        BigDecimal precioBase,
        BigDecimal descuentoUnitario,
        BigDecimal precioFinal,
        BigDecimal tasaImpuesto,
        Long idPromocion,
        String imagenUrl) { }
