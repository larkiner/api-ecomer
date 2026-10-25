package co.ecomers.api.service.tienda;

import co.ecomers.api.dto.tienda.AgregarItemRequest;
import co.ecomers.api.dto.tienda.CambiarCantidadRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.repository.tienda.CarritoRepository;
import org.springframework.stereotype.Service;

/**
 * Carrito del cliente autenticado. El carrito no reserva stock ni guarda precios: eso pasa al
 * crear el pedido. Las reglas (variante activa, cantidades, bloqueo) las aplica PKG_CARRITO.
 */
@Service
public class CarritoService {

    private final CarritoRepository repo;

    public CarritoService(CarritoRepository repo) {
        this.repo = repo;
    }

    public void agregar(ClienteActual cliente, AgregarItemRequest req) {
        repo.agregarItem(cliente, validarVariante(req.idVariante()), req.cantidad());
    }

    public void cambiarCantidad(ClienteActual cliente, long idVariante, CambiarCantidadRequest req) {
        repo.cambiarCantidad(cliente, validarVariante(idVariante), req.cantidad());
    }

    /** Idempotente: quitar una variante que no esta en el carrito no es error. */
    public void quitar(ClienteActual cliente, long idVariante) {
        repo.quitarItem(cliente, validarVariante(idVariante));
    }

    /** Idempotente. */
    public void vaciar(ClienteActual cliente) {
        repo.vaciar(cliente);
    }

    private static long validarVariante(long idVariante) {
        if (idVariante <= 0) {
            throw new NegocioException(CodigoError.VALIDACION, "El id de la variante debe ser positivo");
        }
        return idVariante;
    }
}
