package co.ecomers.api.controller.backoffice;

import co.ecomers.api.dto.backoffice.CrearTrabajadorRequest;
import co.ecomers.api.dto.backoffice.EstadoRequest;
import co.ecomers.api.dto.backoffice.TrabajadorCreadoResponse;
import co.ecomers.api.dto.backoffice.TrabajadorResponse;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.TrabajadorService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/** Trabajadores y sus permisos. Solo el rol ADMIN (lo exige {@code SecurityConfig} y lo vuelve a verificar la base). */
@RestController
@RequestMapping("/api/v1/backoffice/trabajadores")
public class TrabajadorController {

    private final TrabajadorService servicio;

    public TrabajadorController(TrabajadorService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrabajadorCreadoResponse crear(EmpleadoActual admin, @Valid @RequestBody CrearTrabajadorRequest req) {
        return servicio.crear(admin, req);
    }

    @GetMapping
    public List<TrabajadorResponse> listar(EmpleadoActual admin) {
        return servicio.listar(admin);
    }

    @PutMapping("/{idEmpleado}/estado")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarEstado(EmpleadoActual admin, @PathVariable @Positive long idEmpleado, @Valid @RequestBody EstadoRequest req) {
        servicio.cambiarEstado(admin, idEmpleado, req.activo());
    }

    @PutMapping("/{idEmpleado}/permisos/{codigo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void asignarPermiso(EmpleadoActual admin, @PathVariable @Positive long idEmpleado, @PathVariable @Pattern(regexp = "[A-Z_]{3,30}") String codigo) {
        servicio.asignarPermiso(admin, idEmpleado, codigo);
    }

    @DeleteMapping("/{idEmpleado}/permisos/{codigo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quitarPermiso(EmpleadoActual admin, @PathVariable @Positive long idEmpleado, @PathVariable @Pattern(regexp = "[A-Z_]{3,30}") String codigo) {
        servicio.quitarPermiso(admin, idEmpleado, codigo);
    }
}
