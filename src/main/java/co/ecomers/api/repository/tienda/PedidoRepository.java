package co.ecomers.api.repository.tienda;

import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import java.sql.Types;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

@Repository
public class PedidoRepository {

    private static final String PKG = "ECOMERS.PKG_PEDIDO.";

    private final BaseDatosCliente db;

    public PedidoRepository(BaseDatosCliente db) {
        this.db = db;
    }

    /** Convierte el carrito abierto en pedido. {@code idDireccion} nulo = direccion principal. Devuelve ID_PEDIDO. */
    public long crear(ClienteActual c, Long idDireccion) {
        return db.enTransaccion(c.usuarioBd(), s -> s.funcionNumero(PKG + "crear_desde_carrito",
                c.idCliente(), new SqlParameterValue(Types.NUMERIC, idDireccion)));
    }

    public void cancelar(ClienteActual c, long idPedido, String motivo) {
        db.enTransaccionSinResultado(c.usuarioBd(), s -> s.procedimiento(PKG + "cancelar", c.idCliente(), idPedido, motivo));
    }
}
