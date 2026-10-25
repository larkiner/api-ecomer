package co.ecomers.api.service.integracion;

import co.ecomers.api.client.correo.CorreoClient;
import co.ecomers.api.model.EventoCola;
import co.ecomers.api.model.PedidoParaNotificar;
import co.ecomers.api.repository.integracion.ColaRepository;
import co.ecomers.api.repository.integracion.PedidoNotificacionRepository;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumidor de ENVIAR_CORREO: plantillas PEDIDO_PAGADO y CARRITO_ABANDONADO. Un mismo correo
 * puede salir dos veces (entrega "al menos una vez"); se acepta.
 */
@Service
public class CorreoService {

    private final ProcesadorColaService procesador;
    private final ColaRepository cola;
    private final PedidoNotificacionRepository pedidos;
    private final CorreoClient correo;
    private final ObjectMapper json;

    public CorreoService(ProcesadorColaService procesador, ColaRepository cola, PedidoNotificacionRepository pedidos,
                         CorreoClient correo, ObjectMapper json) {
        this.procesador = procesador;
        this.cola = cola;
        this.pedidos = pedidos;
        this.correo = correo;
        this.json = json;
    }

    public int procesarPendientes(int tamanoLote) {
        return procesador.procesar(EventoCola.ENVIAR_CORREO, tamanoLote, this::manejar);
    }

    public void manejar(EventoCola evento) {
        JsonNode payload = json.readTree(evento.payload());
        String plantilla = payload.path("plantilla").asString("");
        switch (plantilla) {
            case "PEDIDO_PAGADO" -> pedidoPagado(payload);
            case "CARRITO_ABANDONADO" -> carritoAbandonado(payload);
            default -> throw new IllegalArgumentException("Plantilla de correo desconocida: " + plantilla);
        }
        cola.completar(evento.id(), EventoCola.ENVIAR_CORREO);
    }

    private void pedidoPagado(JsonNode payload) {
        if (!payload.hasNonNull("id_pedido")) {
            throw new IllegalArgumentException("El evento PEDIDO_PAGADO no trae id_pedido");
        }
        long idPedido = payload.get("id_pedido").asLong();
        PedidoParaNotificar p = pedidos.buscar(idPedido)
                .orElseThrow(() -> new IllegalStateException("El pedido " + idPedido + " no existe"));
        correo.enviar(p.email(), "Tu pedido #" + idPedido + " está pagado", "correo/pedido-pagado", Map.of(
                "nombre", p.nombre(), "idPedido", idPedido, "lineas", p.lineas(),
                "subtotal", p.subtotal(), "impuesto", p.impuesto(), "total", p.total()));
    }

    private void carritoAbandonado(JsonNode payload) {
        String email = payload.path("email").asString("");
        if (email.isBlank()) {
            throw new IllegalArgumentException("El evento CARRITO_ABANDONADO no trae email");
        }
        correo.enviar(email, "Dejaste productos en tu carrito", "correo/carrito-abandonado",
                Map.of("nombre", payload.path("nombre").asString("")));
    }
}
