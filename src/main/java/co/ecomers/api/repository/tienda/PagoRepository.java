package co.ecomers.api.repository.tienda;

import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.IntentoPago;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import java.sql.Types;
import org.springframework.stereotype.Repository;

@Repository
public class PagoRepository {

    private final BaseDatosCliente db;

    public PagoRepository(BaseDatosCliente db) {
        this.db = db;
    }

    /**
     * Crea el intento de pago PENDIENTE por el total del pedido, o devuelve el que ya existe con
     * esa pasarela. El monto nunca lo manda el cliente: sale de la base.
     */
    public IntentoPago iniciar(ClienteActual c, long idPedido, String codigoPasarela) {
        return db.enTransaccion(c.usuarioBd(), s -> s.llamada(
                "{ call ECOMERS.PKG_PEDIDO.iniciar_pago(?, ?, ?, ?, ?, ?) }", cs -> {
                    cs.setLong(1, c.idCliente());
                    cs.setLong(2, idPedido);
                    cs.setString(3, codigoPasarela);
                    cs.registerOutParameter(4, Types.NUMERIC);
                    cs.registerOutParameter(5, Types.VARCHAR);
                    cs.registerOutParameter(6, Types.NUMERIC);
                    cs.execute();
                    return new IntentoPago(cs.getLong(4), cs.getString(5), cs.getBigDecimal(6));
                }));
    }
}
