package co.ecomers.api.dto.backoffice;

import java.math.BigDecimal;

/** Un impuesto del catálogo, para elegirlo al crear un producto. {@code tasa} es una fracción (0.1900 = 19 %). */
public record ImpuestoResponse(long idImpuesto, String nombre, BigDecimal tasa, boolean activo) { }
