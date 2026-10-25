package co.ecomers.api.controller.tienda;

import co.ecomers.api.dto.tienda.AgregarItemRequest;
import co.ecomers.api.dto.tienda.CambiarCantidadRequest;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.service.tienda.CarritoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Carrito del cliente autenticado (rol CLIENTE). No hay GET: el rol cliente no puede leer CARRITO
 * y la base aun no tiene una consulta para eso (ECOMERS_contexto_backend.md, seccion 15.1).
 */
@RestController
@RequestMapping("/api/v1/carrito")
public class CarritoController {

    private final CarritoService servicio;

    public CarritoController(CarritoService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void agregar(ClienteActual cliente, @Valid @RequestBody AgregarItemRequest req) {
        servicio.agregar(cliente, req);
    }

    @PutMapping("/items/{idVariante}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarCantidad(ClienteActual cliente, @PathVariable long idVariante,
                                @Valid @RequestBody CambiarCantidadRequest req) {
        servicio.cambiarCantidad(cliente, idVariante, req);
    }

    @DeleteMapping("/items/{idVariante}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quitar(ClienteActual cliente, @PathVariable long idVariante) {
        servicio.quitar(cliente, idVariante);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void vaciar(ClienteActual cliente) {
        servicio.vaciar(cliente);
    }
}
