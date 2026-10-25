package co.ecomers.api.config;

import co.ecomers.api.repository.oracle.BaseDatosCliente;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import co.ecomers.api.repository.oracle.BaseDatosIntegracion;
import co.ecomers.api.repository.oracle.ReintentoOracle;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tres pools, uno por usuario de aplicacion de Oracle, cada uno con su gestor de transacciones.
 * Cada repositorio recibe por TIPO la conexion de su usuario ({@link BaseDatosCliente},
 * {@link BaseDatosEmpleado}, {@link BaseDatosIntegracion}): no se puede cablear al equivocado,
 * y aunque el codigo tuviera un bug, los grants de Oracle bloquean lo que ese usuario no puede tocar.
 *
 * <p>Los pools arrancan perezosos: la app levanta aunque Oracle no este disponible y el
 * health check lo reporta como DOWN.
 */
@Configuration
public class DataSourceConfig {

    static final String ESQUEMA = "ALTER SESSION SET CURRENT_SCHEMA = ECOMERS";

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSourceCliente(BaseDatosProperties p) {
        return pool("ecomers-cliente", p, p.cliente());
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSourceEmpleado(BaseDatosProperties p) {
        return pool("ecomers-empleado", p, p.empleado());
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSourceIntegracion(BaseDatosProperties p) {
        return pool("ecomers-integracion", p, p.integracion());
    }

    @Bean
    public DataSourceTransactionManager transaccionesCliente(@Qualifier("dataSourceCliente") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public DataSourceTransactionManager transaccionesEmpleado(@Qualifier("dataSourceEmpleado") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public DataSourceTransactionManager transaccionesIntegracion(@Qualifier("dataSourceIntegracion") DataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    public BaseDatosCliente baseDatosCliente(@Qualifier("dataSourceCliente") DataSource ds,
                                             @Qualifier("transaccionesCliente") PlatformTransactionManager tm,
                                             ReintentoOracle reintento) {
        return new BaseDatosCliente(ds, tm, reintento);
    }

    @Bean
    public BaseDatosEmpleado baseDatosEmpleado(@Qualifier("dataSourceEmpleado") DataSource ds,
                                               @Qualifier("transaccionesEmpleado") PlatformTransactionManager tm,
                                               ReintentoOracle reintento) {
        return new BaseDatosEmpleado(ds, tm, reintento);
    }

    @Bean
    public BaseDatosIntegracion baseDatosIntegracion(@Qualifier("dataSourceIntegracion") DataSource ds,
                                                     @Qualifier("transaccionesIntegracion") PlatformTransactionManager tm,
                                                     ReintentoOracle reintento) {
        return new BaseDatosIntegracion(ds, tm, reintento);
    }

    static HikariDataSource pool(String nombre, BaseDatosProperties p, BaseDatosProperties.Usuario u) {
        var ds = new HikariDataSource();
        ds.setPoolName(nombre);
        ds.setJdbcUrl(p.url());
        ds.setUsername(u.username());
        ds.setPassword(u.password());
        ds.setMaximumPoolSize(u.maximumPoolSize());
        ds.setMinimumIdle(u.minimumIdle());
        ds.setConnectionTimeout(p.connectionTimeout().toMillis());
        // Sin sinonimos en la base: las consultas igual se califican con ECOMERS.
        ds.setConnectionInitSql(ESQUEMA);
        return ds;
    }
}
