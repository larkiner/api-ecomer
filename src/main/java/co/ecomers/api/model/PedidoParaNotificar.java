package co.ecomers.api.model;

import java.math.BigDecimal;
import java.util.List;

/** Foto del pedido (PEDIDO + PEDIDO_ITEM) para el correo y el ERP. El correo va al email de facturacion. */
public record PedidoParaNotificar(
        long idPedido,
        String nombre,
        String email,
        BigDecimal subtotal,
        BigDecimal impuesto,
        BigDecimal total,
        List<Linea> lineas) {

    public record Linea(String sku, String nombre, int cantidad, BigDecimal total) { }
}
