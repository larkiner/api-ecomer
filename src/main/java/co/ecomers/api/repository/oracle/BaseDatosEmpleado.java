package co.ecomers.api.repository.oracle;

import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** Conexion como ECOMERS_EMPLEADO_APP: PKG_INVENTARIO y las 10 vistas. Solo para repository.backoffice. */
public class BaseDatosEmpleado extends OracleDb {

    public BaseDatosEmpleado(DataSource ds, PlatformTransactionManager tm, ReintentoOracle reintento) {
        super(ds, tm, reintento);
    }
}
