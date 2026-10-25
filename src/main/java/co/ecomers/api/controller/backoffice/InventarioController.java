package co.ecomers.api.controller.backoffice;

import co.ecomers.api.dto.backoffice.AjusteInventarioRequest;
import co.ecomers.api.dto.backoffice.EntradaInventarioRequest;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.InventarioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Positive;

/** Inventario (rol EMPLEADO). Cada movimiento queda en el kardex con el usuario del empleado. */
@RestController
@RequestMapping("/api/v1/backoffice/inventario")
public class InventarioController {

    private final InventarioService servicio;

    public InventarioController(InventarioService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/{idVariante}/entradas")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void entrada(EmpleadoActual empleado, @PathVariable @Positive long idVariante,
                        @Valid @RequestBody EntradaInventarioRequest req) {
        servicio.registrarEntrada(empleado, idVariante, req);
    }

    @PostMapping("/{idVariante}/ajustes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ajuste(EmpleadoActual empleado, @PathVariable @Positive long idVariante,
                       @Valid @RequestBody AjusteInventarioRequest req) {
        servicio.registrarAjuste(empleado, idVariante, req);
    }
}
