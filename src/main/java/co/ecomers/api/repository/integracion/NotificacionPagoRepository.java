package co.ecomers.api.repository.integracion;

import co.ecomers.api.model.NotificacionPago;
import co.ecomers.api.repository.oracle.BaseDatosIntegracion;
import java.sql.Types;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

@Repository
public class NotificacionPagoRepository {

    private final BaseDatosIntegracion db;

    public NotificacionPagoRepository(BaseDatosIntegracion db) {
        this.db = db;
    }

    /** Guarda el mensaje crudo y encola PAGO_NOTIFICADO en la misma transaccion. Idempotente por evento. */
    public long registrar(NotificacionPago n) {
        return db.enTransaccion("webhook:" + n.pasarela().toLowerCase(), s -> s.funcionNumero(
                "ECOMERS.PKG_PAGO.registrar_notificacion",
                n.pasarela(), n.referencia(), n.idTransaccion(), n.idEvento(), n.estado(), n.monto(), n.metodo(),
                new SqlParameterValue(Types.CLOB, n.payload())));
    }
}
