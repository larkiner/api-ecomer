package co.ecomers.api.controller.backoffice;

import co.ecomers.api.dto.backoffice.EstadoRequest;
import co.ecomers.api.dto.backoffice.ImpuestoResponse;
import co.ecomers.api.dto.backoffice.ProductoCreadoResponse;
import co.ecomers.api.dto.backoffice.ProductoEdicionRequest;
import co.ecomers.api.dto.backoffice.ProductoRequest;
import co.ecomers.api.dto.backoffice.ProductoResponse;
import co.ecomers.api.dto.backoffice.VarianteCreadaResponse;
import co.ecomers.api.dto.backoffice.VarianteEdicionRequest;
import co.ecomers.api.dto.backoffice.VarianteRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Productos, variantes e impuestos. Leer: cualquier empleado. Crear, editar y activar o desactivar: permiso
 * {@code PRODUCTOS} (o admin), que exige {@code SecurityConfig} y vuelve a verificar la base.
 */
@RestController
@RequestMapping("/api/v1/backoffice")
public class ProductoController {

    private final CatalogoAdminService servicio;

    public ProductoController(CatalogoAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/impuestos")
    public List<ImpuestoResponse> impuestos(EmpleadoActual empleado) {
        return servicio.listarImpuestos(empleado);
    }

    @GetMapping("/productos")
    public List<ProductoResponse> listar(EmpleadoActual empleado, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return servicio.listarProductos(empleado, page, size);
    }

    @PostMapping("/productos")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductoCreadoResponse crear(EmpleadoActual empleado, @Valid @RequestBody ProductoRequest req) {
        return servicio.crearProducto(empleado, req);
    }

    @PutMapping("/productos/{idProducto}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void actualizar(EmpleadoActual empleado, @PathVariable long idProducto,
                           @Valid @RequestBody ProductoEdicionRequest req) {
        servicio.actualizarProducto(empleado, idProducto, req);
    }

    @PutMapping("/productos/{idProducto}/estado")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarEstadoProducto(EmpleadoActual empleado, @PathVariable long idProducto,
                                      @Valid @RequestBody EstadoRequest req) {
        servicio.cambiarEstadoProducto(empleado, idProducto, req.activo());
    }

    @PostMapping("/productos/{idProducto}/variantes")
    @ResponseStatus(HttpStatus.CREATED)
    public VarianteCreadaResponse agregarVariante(EmpleadoActual empleado, @PathVariable long idProducto,
                                                  @Valid @RequestBody VarianteRequest req) {
        return servicio.agregarVariante(empleado, idProducto, req);
    }

    @PutMapping("/variantes/{idVariante}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void actualizarVariante(EmpleadoActual empleado, @PathVariable long idVariante,
                                   @Valid @RequestBody VarianteEdicionRequest req) {
        servicio.actualizarVariante(empleado, idVariante, req);
    }

    @PutMapping("/variantes/{idVariante}/estado")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarEstadoVariante(EmpleadoActual empleado, @PathVariable long idVariante,
                                      @Valid @RequestBody EstadoRequest req) {
        servicio.cambiarEstadoVariante(empleado, idVariante, req.activo());
    }
}
