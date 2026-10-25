package co.ecomers.api.repository.tienda;

import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import org.springframework.stereotype.Repository;

/**
 * Escrituras del carrito: cada una es una llamada a PKG_CARRITO en su propia transaccion. El
 * rol cliente no puede leer CARRITO; por eso no hay consulta del carrito (falta en la base).
 */
@Repository
public class CarritoRepository {

    private static final String PKG = "ECOMERS.PKG_CARRITO.";

    private final BaseDatosCliente db;

    public CarritoRepository(BaseDatosCliente db) {
        this.db = db;
    }

    public void agregarItem(ClienteActual c, long idVariante, int cantidad) {
        db.enTransaccionSinResultado(c.usuarioBd(),
                s -> s.procedimiento(PKG + "agregar_item", c.idCliente(), idVariante, cantidad));
    }

    public void cambiarCantidad(ClienteActual c, long idVariante, int cantidad) {
        db.enTransaccionSinResultado(c.usuarioBd(),
                s -> s.procedimiento(PKG + "cambiar_cantidad", c.idCliente(), idVariante, cantidad));
    }

    public void quitarItem(ClienteActual c, long idVariante) {
        db.enTransaccionSinResultado(c.usuarioBd(), s -> s.procedimiento(PKG + "quitar_item", c.idCliente(), idVariante));
    }

    public void vaciar(ClienteActual c) {
        db.enTransaccionSinResultado(c.usuarioBd(), s -> s.procedimiento(PKG + "vaciar", c.idCliente()));
    }
}
