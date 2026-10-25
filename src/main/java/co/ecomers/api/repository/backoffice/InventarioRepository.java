package co.ecomers.api.repository.backoffice;

import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import org.springframework.stereotype.Repository;

/** Movimientos de inventario por PKG_INVENTARIO. El kardex guarda el usuario del empleado (SET_IDENTIFIER). */
@Repository
public class InventarioRepository {

    private static final String PKG = "ECOMERS.PKG_INVENTARIO.";

    private final BaseDatosEmpleado db;

    public InventarioRepository(BaseDatosEmpleado db) {
        this.db = db;
    }

    public void entrada(EmpleadoActual e, long idVariante, int cantidad, String observacion) {
        db.enTransaccionSinResultado(e.usuarioBd(),
                s -> s.procedimiento(PKG + "entrada", idVariante, cantidad, observacion));
    }

    public void ajuste(EmpleadoActual e, long idVariante, int cantidad, String observacion) {
        db.enTransaccionSinResultado(e.usuarioBd(),
                s -> s.procedimiento(PKG + "ajuste", idVariante, cantidad, observacion));
    }
}
