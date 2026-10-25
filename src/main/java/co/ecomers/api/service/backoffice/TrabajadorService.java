package co.ecomers.api.service.backoffice;

import co.ecomers.api.dto.backoffice.CrearTrabajadorRequest;
import co.ecomers.api.dto.backoffice.TrabajadorCreadoResponse;
import co.ecomers.api.dto.backoffice.TrabajadorResponse;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.TrabajadorRepository;
import co.ecomers.api.security.InvalidacionSesionService;
import co.ecomers.api.service.auth.PoliticaPassword;
import java.util.List;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Trabajadores y permisos delegables (solo el admin; la base lo verifica). Cuando se desactiva a un trabajador o se le
 * cambian los permisos, sus sesiones se invalidan: el token lleva los permisos y, sin esto, seguiría sirviendo hasta que
 * venza.
 */
@Service
public class TrabajadorService {

    private final TrabajadorRepository repositorio;
    private final PasswordEncoder passwordEncoder;
    private final InvalidacionSesionService invalidacion;

    public TrabajadorService(TrabajadorRepository repositorio, PasswordEncoder passwordEncoder,
                             InvalidacionSesionService invalidacion) {
        this.repositorio = repositorio;
        this.passwordEncoder = passwordEncoder;
        this.invalidacion = invalidacion;
    }

    public TrabajadorCreadoResponse crear(EmpleadoActual admin, CrearTrabajadorRequest req) {
        PoliticaPassword.validar(req.password());
        long id = repositorio.crear(admin, req.nombre().trim(), req.email().trim().toLowerCase(Locale.ROOT),
                passwordEncoder.encode(req.password()));
        return new TrabajadorCreadoResponse(id);
    }

    public List<TrabajadorResponse> listar(EmpleadoActual admin) {
        return repositorio.listar(admin);
    }

    /** Desactivar cierra las sesiones del trabajador; reactivarlo no hace falta. */
    public void cambiarEstado(EmpleadoActual admin, long idEmpleado, boolean activo) {
        String correo = repositorio.cambiarEstado(admin, idEmpleado, activo);
        if (!activo) {
            invalidacion.invalidarSesionesDe(correo);
        }
    }

    public void asignarPermiso(EmpleadoActual admin, long idEmpleado, String codigo) {
        invalidacion.invalidarSesionesDe(repositorio.asignarPermiso(admin, idEmpleado, codigo));
    }

    public void quitarPermiso(EmpleadoActual admin, long idEmpleado, String codigo) {
        invalidacion.invalidarSesionesDe(repositorio.quitarPermiso(admin, idEmpleado, codigo));
    }
}
