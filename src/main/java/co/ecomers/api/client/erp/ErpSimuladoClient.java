package co.ecomers.api.client.erp;

import co.ecomers.api.model.PedidoParaNotificar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** ERP de mentira: no llama a nadie y devuelve "ERP-idPedido". Se reemplaza cuando se defina el ERP real. */
@Component
@ConditionalOnProperty(name = "app.erp.modo", havingValue = "simulado", matchIfMissing = true)
public class ErpSimuladoClient implements ErpClient {

    private static final Logger log = LoggerFactory.getLogger(ErpSimuladoClient.class);

    @Override
    public String enviarPedido(PedidoParaNotificar pedido) {
        log.info("ERP simulado: pedido {} por {}", pedido.idPedido(), pedido.total());
        return "ERP-" + pedido.idPedido();
    }
}
