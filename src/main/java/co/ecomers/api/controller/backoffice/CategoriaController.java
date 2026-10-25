package co.ecomers.api.controller.backoffice;

import co.ecomers.api.dto.backoffice.CategoriaCreadaResponse;
import co.ecomers.api.dto.backoffice.CategoriaRequest;
import co.ecomers.api.dto.backoffice.CategoriaResponse;
import co.ecomers.api.dto.backoffice.EstadoRequest;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.CatalogoAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Positive;

/**
 * Categorías. Leer: cualquier empleado. Crear, editar y activar o desactivar: permiso {@code CATEGORIAS} (o admin), que
 * exige {@code SecurityConfig} y vuelve a verificar la base.
 */
@RestController
@RequestMapping("/api/v1/backoffice/categorias")
public class CategoriaController {

    private final CatalogoAdminService servicio;

    public CategoriaController(CatalogoAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<CategoriaResponse> listar(EmpleadoActual empleado) {
        return servicio.listarCategorias(empleado);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CategoriaCreadaResponse crear(EmpleadoActual empleado, @Valid @RequestBody CategoriaRequest req) {
        return servicio.crearCategoria(empleado, req);
    }

    @PutMapping("/{idCategoria}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void actualizar(EmpleadoActual empleado, @PathVariable @Positive long idCategoria,
                           @Valid @RequestBody CategoriaRequest req) {
        servicio.actualizarCategoria(empleado, idCategoria, req);
    }

    @PutMapping("/{idCategoria}/estado")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarEstado(EmpleadoActual empleado, @PathVariable @Positive long idCategoria,
                              @Valid @RequestBody EstadoRequest req) {
        servicio.cambiarEstadoCategoria(empleado, idCategoria, req.activo());
    }
}
