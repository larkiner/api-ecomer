package co.ecomers.api.repository.backoffice;

import co.ecomers.api.model.Credencial;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Cuentas de empleado: lectura de la credencial para el login y cambio de contraseña, por PKG_EMPLEADO (rol
 * empleado). Los empleados los da de alta el administrador de la base, no esta API.
 */
@Repository
public class EmpleadoAuthRepository {

    private static final String PKG = "ECOMERS.PKG_EMPLEADO.";
    private static final String USUARIO_AUTH = "auth:empleado";

    private final BaseDatosEmpleado db;

    public EmpleadoAuthRepository(BaseDatosEmpleado db) {
        this.db = db;
    }

    public Optional<Credencial> buscarPorEmail(String email) {
        return db.lectura(USUARIO_AUTH, s -> s.funcionCursor(PKG + "credencial_por_email",
                        (rs, n) -> new Credencial(rs.getLong("ID_EMPLEADO"), rs.getString("EMAIL"),
                                rs.getString("PASSWORD_HASH"), rs.getInt("ACTIVO") == 1, rolDe(rs.getString("ROL"))),
                        email))
                .stream().findFirst();
    }

    /** Códigos de los permisos del empleado: todos los activos si es ADMIN, los asignados si es TRABAJADOR. */
    public List<String> permisosDe(long idEmpleado) {
        return db.lectura(USUARIO_AUTH, s -> s.funcionCursor(PKG + "permisos_de",
                (rs, n) -> rs.getString("CODIGO"), idEmpleado));
    }

    /** El ROL de la base (ADMIN o TRABAJADOR) al rol del token (ADMIN o EMPLEADO). */
    private static String rolDe(String rolBase) {
        return "ADMIN".equals(rolBase) ? Credencial.ROL_ADMIN : Credencial.ROL_EMPLEADO;
    }

    public void cambiarPassword(long idEmpleado, String passwordHash) {
        db.enTransaccionSinResultado("emp:" + idEmpleado,
                s -> s.procedimiento(PKG + "cambiar_password", idEmpleado, passwordHash));
    }
}
