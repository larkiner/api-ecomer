package co.ecomers.api.service.tienda;

import co.ecomers.api.dto.tienda.CancelarPedidoRequest;
import co.ecomers.api.dto.tienda.CrearPedidoRequest;
import co.ecomers.api.dto.tienda.PedidoCreadoResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.repository.tienda.PedidoRepository;
import org.springframework.stereotype.Service;

/**
 * Pedidos del cliente autenticado. Crear un pedido congela precios, descuentos e impuestos, copia
 * la facturacion y reserva el stock (todo o nada, en PKG_PEDIDO). Un pedido de otro cliente
 * responde igual que uno inexistente (404), para no permitir enumerar IDs.
 */
@Service
public class PedidoService {

    private final PedidoRepository repo;

    public PedidoService(PedidoRepository repo) {
        this.repo = repo;
    }

    /** {@code req} nulo o sin direccion = direccion principal del cliente. */
    public PedidoCreadoResponse crear(ClienteActual cliente, CrearPedidoRequest req) {
        Long direccion = req == null ? null : req.idDireccion();
        return new PedidoCreadoResponse(repo.crear(cliente, direccion));
    }

    /** Solo pedidos en PENDIENTE_PAGO; libera el stock. Idempotente. */
    public void cancelar(ClienteActual cliente, long idPedido, CancelarPedidoRequest req) {
        validarPedido(idPedido);
        String motivo = req == null || req.motivo() == null || req.motivo().isBlank() ? null : req.motivo().trim();
        repo.cancelar(cliente, idPedido, motivo);
    }

    static void validarPedido(long idPedido) {
        if (idPedido <= 0) {
            throw new NegocioException(CodigoError.PEDIDO_NO_ENCONTRADO, "El pedido no existe");
        }
    }
}
