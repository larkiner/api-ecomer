package co.ecomers.api.service.tienda;

import co.ecomers.api.client.wompi.CheckoutWompi;
import co.ecomers.api.client.wompi.EventoWompi;
import co.ecomers.api.dto.tienda.IniciarPagoResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.IntentoPago;
import co.ecomers.api.repository.tienda.PagoRepository;
import co.ecomers.api.util.Dinero;
import org.springframework.stereotype.Service;

/**
 * Inicio del pago con Wompi. La API nunca marca nada como pagado: la aprobacion llega por el
 * webhook firmado y la aplica el JOB de la base. El monto sale siempre de la base.
 */
@Service
public class PagoService {

    private final PagoRepository repo;
    private final CheckoutWompi checkout;

    public PagoService(PagoRepository repo, CheckoutWompi checkout) {
        this.repo = repo;
        this.checkout = checkout;
    }

    public IniciarPagoResponse iniciar(ClienteActual cliente, long idPedido) {
        PedidoService.validarPedido(idPedido);
        // Sin llaves no se puede redirigir al cliente: no se crea un intento de pago huerfano.
        if (!checkout.configurado()) {
            throw new NegocioException(CodigoError.PASARELA_NO_CONFIGURADA, "El pago en línea no está disponible en este momento");
        }
        IntentoPago intento = repo.iniciar(cliente, idPedido, EventoWompi.PASARELA);
        long centavos = Dinero.aCentavos(intento.monto());
        CheckoutWompi.Checkout c = checkout.crear(intento.referencia(), centavos, idPedido);
        return new IniciarPagoResponse(intento.referencia(), centavos, CheckoutWompi.MONEDA, c.publicKey(),
                c.firmaIntegridad(), c.redirectUrl(), c.url());
    }
}
