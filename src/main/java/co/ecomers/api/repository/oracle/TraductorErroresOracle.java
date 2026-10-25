package co.ecomers.api.repository.oracle;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.UncategorizedSQLException;

/** Convierte excepciones de JDBC con un ORA-20xxx en {@link NegocioException}. Mapea por codigo, nunca por texto. */
public final class TraductorErroresOracle {

    private TraductorErroresOracle() { }

    public static RuntimeException traducir(RuntimeException e) {
        if (e instanceof NegocioException) {
            return e;
        }
        SQLException sql = buscarSql(e);
        if (sql == null) {
            return e;
        }
        return CodigoError.deOracle(sql.getErrorCode())
                .<RuntimeException>map(c -> new NegocioException(c, limpiarMensaje(sql.getMessage()), sql.getErrorCode()))
                .orElse(e);
    }

    /** Para JDBC directo: NegocioException si el codigo esta mapeado; si no, una excepcion de Spring. */
    public static RuntimeException traducir(SQLException e, String sql) {
        return traducir(new UncategorizedSQLException("llamada a la base", sql, e));
    }

    public static Optional<Integer> codigoOracle(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NegocioException n && n.codigoOracle() > 0) {
                return Optional.of(n.codigoOracle());
            }
            if (t instanceof SQLException s && s.getErrorCode() > 0) {
                return Optional.of(s.getErrorCode());
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return Optional.empty();
    }

    /** Quita el prefijo "ORA-20xxx: " y las lineas de traza ORA-06512. */
    public static String limpiarMensaje(String ora) {
        if (ora == null) {
            return "";
        }
        String primera = ora.lines().filter(l -> !l.startsWith("ORA-06512")).findFirst().orElse(ora);
        return primera.replaceFirst("^ORA-\\d+:\\s*", "").trim();
    }

    private static SQLException buscarSql(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s) {
                return s;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
