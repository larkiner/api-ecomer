package co.ecomers.api.repository.oracle;

import co.ecomers.api.util.FechaBd;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/** Lectura de columnas que pueden venir en NULL o que necesitan conversion de tipo. */
public final class Filas {

    private Filas() { }

    public static Long longONulo(ResultSet rs, String columna) throws SQLException {
        long valor = rs.getLong(columna);
        return rs.wasNull() ? null : valor;
    }

    /** TIMESTAMP sin zona (hora de Colombia) como OffsetDateTime -05:00. */
    public static OffsetDateTime fechaHora(ResultSet rs, String columna) throws SQLException {
        return FechaBd.aOffset(rs.getObject(columna, LocalDateTime.class));
    }

    /** DATE (por ejemplo un TRUNC de fecha) como LocalDate. */
    public static LocalDate fecha(ResultSet rs, String columna) throws SQLException {
        Date d = rs.getDate(columna);
        return d == null ? null : d.toLocalDate();
    }

    /** RAW(16) como hexadecimal de 32 caracteres. */
    public static String hex(ResultSet rs, String columna) throws SQLException {
        byte[] bytes = rs.getBytes(columna);
        return bytes == null ? null : java.util.HexFormat.of().withUpperCase().formatHex(bytes);
    }
}
