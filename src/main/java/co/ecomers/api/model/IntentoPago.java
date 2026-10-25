package co.ecomers.api.model;

import java.math.BigDecimal;

/** Intento de pago creado por PKG_PEDIDO.iniciar_pago. {@code referencia} es el ID publico del pago (hex de 32). */
public record IntentoPago(long idPago, String referencia, BigDecimal monto) { }
