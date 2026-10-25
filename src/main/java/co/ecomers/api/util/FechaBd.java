package co.ecomers.api.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Las fechas de la base son TIMESTAMP sin zona con la hora de Colombia (-05:00, sin horario de
 * verano). Hacia afuera se exponen con el desfase explicito para que el front no las malinterprete.
 */
public final class FechaBd {

    public static final ZoneOffset COLOMBIA = ZoneOffset.of("-05:00");

    private FechaBd() { }

    public static OffsetDateTime aOffset(LocalDateTime t) {
        return t == null ? null : t.atOffset(COLOMBIA);
    }

    /** "Hoy" segun el servidor de base de datos (Colombia), para rangos de reportes. */
    public static LocalDate hoy() {
        return LocalDate.now(COLOMBIA);
    }
}
