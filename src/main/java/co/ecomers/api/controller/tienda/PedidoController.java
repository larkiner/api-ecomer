package co.ecomers.api.controller.tienda;

import co.ecomers.api.dto.tienda.CancelarPedidoRequest;
import co.ecomers.api.dto.tienda.CrearPedidoRequest;
import co.ecomers.api.dto.tienda.IniciarPagoResponse;
import co.ecomers.api.dto.tienda.PedidoCreadoResponse;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.service.tienda.PagoService;
import co.ecomers.api.service.tienda.PedidoService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pedidos del cliente autenticado (rol CLIENTE). Flujo: carrito -> crear pedido (reserva stock)
 * -> iniciar pago (Wompi) -> el webhook confirma. Las rutas usan el ID_PEDIDO numerico porque el
 * rol cliente no puede leer PEDIDO; la base valida que el pedido sea del cliente (404 si no).
 */
@RestController
@RequestMapping("/api/v1/pedidos")
public class PedidoController {

    private final PedidoService pedidos;
    private final PagoService pagos;

    public PedidoController(PedidoService pedidos, PagoService pagos) {
        this.pedidos = pedidos;
        this.pagos = pagos;
    }

    @PostMapping
    public ResponseEntity<PedidoCreadoResponse> crear(ClienteActual cliente,
                                                      @Valid @RequestBody(required = false) CrearPedidoRequest req) {
        PedidoCreadoResponse creado = pedidos.crear(cliente, req);
        return ResponseEntity.created(URI.create("/api/v1/pedidos/" + creado.idPedido())).body(creado);
    }

    @PostMapping("/{idPedido}/cancelacion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelar(ClienteActual cliente, @PathVariable long idPedido,
                         @Valid @RequestBody(required = false) CancelarPedidoRequest req) {
        pedidos.cancelar(cliente, idPedido, req);
    }

    /** Crea (o recupera) el intento de pago y devuelve el enlace firmado al checkout de Wompi. */
    @PostMapping("/{idPedido}/pagos")
    @ResponseStatus(HttpStatus.CREATED)
    public IniciarPagoResponse iniciarPago(ClienteActual cliente, @PathVariable long idPedido) {
        return pagos.iniciar(cliente, idPedido);
    }
}
