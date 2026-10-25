package co.ecomers.api.client.erp;

import co.ecomers.api.model.PedidoParaNotificar;

/** Puerto hacia el ERP. Debe ser idempotente por pedido: la cola entrega "al menos una vez". */
public interface ErpClient {

    /** Envia el pedido al ERP y devuelve el id que el ERP le asigno. */
    String enviarPedido(PedidoParaNotificar pedido);
}
