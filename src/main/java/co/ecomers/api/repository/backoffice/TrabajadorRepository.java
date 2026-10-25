package co.ecomers.api.repository.backoffice;

import co.ecomers.api.dto.backoffice.TrabajadorResponse;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import co.ecomers.api.repository.oracle.Filas;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Gestión de trabajadores y permisos por PKG_EMPLEADO (solo la usa un admin; la base lo verifica con el ID que recibe
 * primero). Las rutinas que cambian algo devuelven el correo del trabajador para invalidar sus sesiones.
 */
@Repository
public class TrabajadorRepository {

    private static final String PKG = "ECOMERS.PKG_EMPLEADO.";

    private final BaseDatosEmpleado db;

    public TrabajadorRepository(BaseDatosEmpleado db) {
        this.db = db;
    }

    public long crear(EmpleadoActual admin, String nombre, String email, String passwordHash) {
        return db.enTransaccion(admin.usuarioBd(), s -> s.funcionNumero(PKG + "crear_trabajador",
                admin.idEmpleado(), nombre, email, passwordHash));
    }

    public List<TrabajadorResponse> listar(EmpleadoActual admin) {
        return db.lectura(admin.usuarioBd(), s -> s.funcionCursor(PKG + "listar_trabajadores",
                (rs, n) -> new TrabajadorResponse(rs.getLong("ID_EMPLEADO"), rs.getString("NOMBRE"),
                        rs.getString("EMAIL"), rs.getInt("ACTIVO") == 1, Filas.fechaHora(rs, "FECHA_CREACION"),
                        codigos(rs.getString("PERMISOS"))),
                admin.idEmpleado()));
    }

    /** Devuelve el correo del trabajador. */
    public String cambiarEstado(EmpleadoActual admin, long idEmpleado, boolean activo) {
        return db.enTransaccion(admin.usuarioBd(), s -> s.funcionTexto(PKG + "cambiar_estado",
                admin.idEmpleado(), idEmpleado, activo ? 1 : 0));
    }

    /** Devuelve el correo del trabajador. */
    public String asignarPermiso(EmpleadoActual admin, long idEmpleado, String codigo) {
        return db.enTransaccion(admin.usuarioBd(), s -> s.funcionTexto(PKG + "asignar_permiso",
                admin.idEmpleado(), idEmpleado, codigo));
    }

    /** Devuelve el correo del trabajador. */
    public String quitarPermiso(EmpleadoActual admin, long idEmpleado, String codigo) {
        return db.enTransaccion(admin.usuarioBd(), s -> s.funcionTexto(PKG + "quitar_permiso",
                admin.idEmpleado(), idEmpleado, codigo));
    }

    /** "A,B" pasa a ["A", "B"]; nulo o vacío pasa a lista vacía. */
    private static List<String> codigos(String permisos) {
        return permisos == null || permisos.isBlank() ? List.of() : Arrays.asList(permisos.split(","));
    }
}
