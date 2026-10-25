package co.ecomers.api.repository.oracle;

import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** Conexion como ECOMERS_CLIENTE_APP: PKG_CARRITO, PKG_PEDIDO y V_PRECIO_VIGENTE. Solo para repository.tienda. */
public class BaseDatosCliente extends OracleDb {

    public BaseDatosCliente(DataSource ds, PlatformTransactionManager tm, ReintentoOracle reintento) {
        super(ds, tm, reintento);
    }
}
