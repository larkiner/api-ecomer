package co.ecomers.api.repository.oracle;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Punto de entrada a Oracle para un usuario de aplicacion. Cada llamada es una transaccion:
 * fija {@code DBMS_SESSION.SET_IDENTIFIER} con el usuario final (trazabilidad en el historial
 * y el kardex), ejecuta el trabajo, confirma o deshace, y limpia el identificador. Reintenta
 * toda la transaccion ante los errores transitorios de la base.
 *
 * <p>Los paquetes de ECOMERS no hacen COMMIT y cada procedimiento es atomico, por eso una
 * operacion de negocio = una transaccion de esta clase.
 *
 * <p>No se inyecta directamente: cada repositorio recibe la subclase de SU usuario
 * ({@link BaseDatosCliente}, {@link BaseDatosEmpleado} o {@link BaseDatosIntegracion}), asi
 * es imposible cablear un repositorio al usuario equivocado.
 */
public abstract class OracleDb {

    private static final Logger log = LoggerFactory.getLogger(OracleDb.class);
    private static final int MAX_IDENTIFICADOR = 64;

    private final DataSource ds;
    private final PlatformTransactionManager tm;
    private final ReintentoOracle reintento;

    protected OracleDb(DataSource ds, PlatformTransactionManager tm, ReintentoOracle reintento) {
        this.ds = ds;
        this.tm = tm;
        this.reintento = reintento;
    }

    public <T> T enTransaccion(String usuarioFinal, Function<Sesion, T> trabajo) {
        return ejecutar(usuarioFinal, false, trabajo);
    }

    public void enTransaccionSinResultado(String usuarioFinal, Consumer<Sesion> trabajo) {
        ejecutar(usuarioFinal, false, s -> {
            trabajo.accept(s);
            return null;
        });
    }

    /** Solo lectura: siempre termina con ROLLBACK. */
    public <T> T lectura(String usuarioFinal, Function<Sesion, T> trabajo) {
        return ejecutar(usuarioFinal, true, trabajo);
    }

    private <T> T ejecutar(String usuarioFinal, boolean soloLectura, Function<Sesion, T> trabajo) {
        return reintento.ejecutar(() -> {
            var plantilla = new TransactionTemplate(tm);
            plantilla.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            plantilla.setReadOnly(soloLectura);
            try {
                return plantilla.execute(status -> {
                    Connection con = DataSourceUtils.getConnection(ds);
                    try {
                        fijarIdentificador(con, usuarioFinal);
                        T resultado = trabajo.apply(new Sesion(ds, con));
                        if (soloLectura) {
                            status.setRollbackOnly();
                        }
                        return resultado;
                    } finally {
                        limpiarIdentificador(con);
                        DataSourceUtils.releaseConnection(con, ds);
                    }
                });
            } catch (RuntimeException e) {
                throw TraductorErroresOracle.traducir(e);
            }
        });
    }

    private static void fijarIdentificador(Connection con, String usuario) {
        String valor = usuario == null || usuario.isBlank() ? "anonimo" : usuario;
        if (valor.length() > MAX_IDENTIFICADOR) {
            valor = valor.substring(0, MAX_IDENTIFICADOR);
        }
        try (CallableStatement cs = con.prepareCall("{ call DBMS_SESSION.SET_IDENTIFIER(?) }")) {
            cs.setString(1, valor);
            cs.execute();
        } catch (SQLException e) {
            throw TraductorErroresOracle.traducir(e, "DBMS_SESSION.SET_IDENTIFIER");
        }
    }

    private static void limpiarIdentificador(Connection con) {
        try (CallableStatement cs = con.prepareCall("{ call DBMS_SESSION.CLEAR_IDENTIFIER }")) {
            cs.execute();
        } catch (SQLException e) {
            log.warn("No se pudo limpiar el identificador de sesion: {}", e.getMessage());
        }
    }
}
