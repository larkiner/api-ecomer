package co.ecomers.api.repository.oracle;

import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Conexion como ECOMERS_INTEGRACION_APP: PKG_PAGO, PKG_COLA, PEDIDO y PEDIDO_ITEM. Solo para
 * repository.integracion. Es la unica que puede registrar notificaciones de pago.
 */
public class BaseDatosIntegracion extends OracleDb {

    public BaseDatosIntegracion(DataSource ds, PlatformTransactionManager tm, ReintentoOracle reintento) {
        super(ds, tm, reintento);
    }
}
