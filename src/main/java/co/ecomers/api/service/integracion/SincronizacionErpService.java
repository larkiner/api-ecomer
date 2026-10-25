package co.ecomers.api.service.integracion;

import co.ecomers.api.client.erp.ErpClient;
import co.ecomers.api.model.EventoCola;
import co.ecomers.api.model.PedidoParaNotificar;
import co.ecomers.api.repository.integracion.ColaRepository;
import co.ecomers.api.repository.integracion.PedidoNotificacionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumidor de SINCRONIZAR_ERP. Siempre se completa con completar_erp, que guarda PEDIDO_ERP y
 * cierra el evento en una sola transaccion (con completar, el JOB de revision falla con -20211).
 */
@Service
public class SincronizacionErpService {

    private final ProcesadorColaService procesador;
    private final ColaRepository cola;
    private final PedidoNotificacionRepository pedidos;
    private final ErpClient erp;
    private final ObjectMapper json;

    public SincronizacionErpService(ProcesadorColaService procesador, ColaRepository cola,
                                    PedidoNotificacionRepository pedidos, ErpClient erp, ObjectMapper json) {
        this.procesador = procesador;
        this.cola = cola;
        this.pedidos = pedidos;
        this.erp = erp;
        this.json = json;
    }

    public int procesarPendientes(int tamanoLote) {
        return procesador.procesar(EventoCola.SINCRONIZAR_ERP, tamanoLote, this::manejar);
    }

    public void manejar(EventoCola evento) {
        JsonNode payload = json.readTree(evento.payload());
        if (!payload.hasNonNull("id_pedido")) {
            throw new IllegalArgumentException("El evento SINCRONIZAR_ERP no trae id_pedido");
        }
        long idPedido = payload.get("id_pedido").asLong();
        PedidoParaNotificar pedido = pedidos.buscar(idPedido)
                .orElseThrow(() -> new IllegalStateException("El pedido " + idPedido + " no existe"));
        String idErp = erp.enviarPedido(pedido);
        cola.completarErp(evento.id(), idPedido, idErp);
    }
}
