package co.ecomers.api.repository.oracle;

import java.io.StringReader;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import oracle.jdbc.OracleTypes;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Conexion de una transaccion de {@link OracleDb}: todo comparte la misma conexion y el mismo COMMIT. */
public final class Sesion {

    private final DataSource ds;
    private final Connection con;

    Sesion(DataSource ds, Connection con) {
        this.ds = ds;
        this.con = con;
    }

    /** SELECT parametrizados; participa en la transaccion actual. */
    public JdbcClient sql() {
        return JdbcClient.create(ds);
    }

    /** {@code { call nombre(?, ?) }}. */
    public void procedimiento(String nombre, Object... args) {
        String sql = "{ call " + nombre + "(" + marcadores(args.length) + ") }";
        ejecutar(sql, cs -> {
            enlazar(cs, 1, args);
            cs.execute();
            return null;
        });
    }

    /** {@code { ? = call nombre(...) }} que devuelve un NUMBER. */
    public long funcionNumero(String nombre, Object... args) {
        String sql = "{ ? = call " + nombre + "(" + marcadores(args.length) + ") }";
        return ejecutar(sql, cs -> {
            cs.registerOutParameter(1, Types.NUMERIC);
            enlazar(cs, 2, args);
            cs.execute();
            return cs.getBigDecimal(1).longValueExact();
        });
    }

    /** {@code { ? = call nombre(...) }} que devuelve un VARCHAR2 (o {@code null}). */
    public String funcionTexto(String nombre, Object... args) {
        String sql = "{ ? = call " + nombre + "(" + marcadores(args.length) + ") }";
        return ejecutar(sql, cs -> {
            cs.registerOutParameter(1, Types.VARCHAR);
            enlazar(cs, 2, args);
            cs.execute();
            return cs.getString(1);
        });
    }

    /** Funcion que devuelve un SYS_REFCURSOR; se leen todas las filas dentro de la transaccion. */
    public <R> List<R> funcionCursor(String nombre, RowMapper<R> mapper, Object... args) {
        String sql = "{ ? = call " + nombre + "(" + marcadores(args.length) + ") }";
        return ejecutar(sql, cs -> {
            cs.registerOutParameter(1, OracleTypes.CURSOR);
            enlazar(cs, 2, args);
            cs.execute();
            List<R> filas = new ArrayList<>();
            try (ResultSet rs = (ResultSet) cs.getObject(1)) {
                int n = 0;
                while (rs.next()) {
                    filas.add(mapper.mapRow(rs, n++));
                }
            }
            return filas;
        });
    }

    /** Llamada libre, por ejemplo con parametros OUT. */
    public <T> T llamada(String sqlCallable, CallableStatementCallback<T> cb) {
        return ejecutar(sqlCallable, cb);
    }

    private <T> T ejecutar(String sql, CallableStatementCallback<T> cb) {
        try (CallableStatement cs = con.prepareCall(sql)) {
            return cb.doInCallableStatement(cs);
        } catch (SQLException e) {
            throw TraductorErroresOracle.traducir(e, sql);
        }
    }

    private static String marcadores(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private static void enlazar(CallableStatement cs, int desde, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int idx = desde + i;
            if (a == null) {
                cs.setNull(idx, Types.VARCHAR);
            } else if (a instanceof SqlParameterValue p && p.getSqlType() == Types.CLOB) {
                // ojdbc no acepta setObject(String, CLOB) (ORA-17004): el texto va como stream.
                if (p.getValue() == null) {
                    cs.setNull(idx, Types.CLOB);
                } else {
                    String texto = p.getValue().toString();
                    cs.setCharacterStream(idx, new StringReader(texto), (long) texto.length());
                }
            } else if (a instanceof SqlParameterValue p) {
                cs.setObject(idx, p.getValue(), p.getSqlType());
            } else {
                cs.setObject(idx, a);
            }
        }
    }
}
