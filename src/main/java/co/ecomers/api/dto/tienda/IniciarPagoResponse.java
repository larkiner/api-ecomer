package co.ecomers.api.dto.tienda;

/**
 * Datos para llevar al cliente al Web Checkout de Wompi. {@code checkoutUrl} ya viene armada y
 * firmada; los demas campos sirven si el front usa el widget de Wompi.
 */
public record IniciarPagoResponse(
        String referencia,
        long montoEnCentavos,
        String moneda,
        String publicKey,
        String firmaIntegridad,
        String redirectUrl,
        String checkoutUrl) { }
