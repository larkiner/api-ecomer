package co.ecomers.api.util;

import java.math.BigDecimal;

/** Montos en pesos colombianos (COP) con dos decimales, como NUMBER(14,2) en la base. */
public final class Dinero {

    private Dinero() { }

    /** Pesos a centavos, exacto. Lanza {@link ArithmeticException} si el monto tiene mas de dos decimales. */
    public static long aCentavos(BigDecimal monto) {
        return monto.movePointRight(2).longValueExact();
    }

    /** Centavos a pesos con escala 2. */
    public static BigDecimal deCentavos(long centavos) {
        return BigDecimal.valueOf(centavos, 2);
    }
}
