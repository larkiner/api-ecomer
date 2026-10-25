package co.ecomers.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import co.ecomers.api.util.Dinero;
import co.ecomers.api.util.FechaBd;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ConfiguracionTest {

    // ---------------- Pools de Oracle ----------------

    private static BaseDatosProperties propiedades() {
        var u = new BaseDatosProperties.Usuario("ECOMERS_CLIENTE_APP", "clave-secreta", 10, 2);
        return new BaseDatosProperties("jdbc:oracle:thin:@//localhost:1521/XEPDB1", Duration.ofSeconds(5), u, u, u);
    }

    @Test
    void poolFijaElEsquemaYNoArrancaHastaElPrimerUso() {
        var p = propiedades();
        try (var ds = DataSourceConfig.pool("ecomers-cliente", p, p.cliente())) {
            assertThat(ds.getConnectionInitSql()).isEqualTo("ALTER SESSION SET CURRENT_SCHEMA = ECOMERS");
            assertThat(ds.getPoolName()).isEqualTo("ecomers-cliente");
            assertThat(ds.getMaximumPoolSize()).isEqualTo(10);
            assertThat(ds.getConnectionTimeout()).isEqualTo(5_000L);
            assertThat(ds.isRunning()).isFalse();
        }
    }

    @Test
    void lasPropiedadesNuncaImprimenLaClave() {
        assertThat(propiedades().toString()).doesNotContain("clave-secreta");
    }

    // ---------------- Utilidades ----------------

    @Test
    void dineroACentavosExactos() {
        assertThat(Dinero.aCentavos(new BigDecimal("238000.00"))).isEqualTo(23_800_000L);
        assertThat(Dinero.deCentavos(23_800_000L)).isEqualByComparingTo("238000.00");
        assertThat(Dinero.deCentavos(23_800_000L).scale()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Dinero.aCentavos(new BigDecimal("1.005")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void fechasDeLaBaseSalenConElDesfaseDeColombia() {
        assertThat(FechaBd.aOffset(LocalDateTime.of(2026, 10, 18, 10, 0)).toString()).isEqualTo("2026-10-18T10:00-05:00");
        assertThat(FechaBd.aOffset(null)).isNull();
    }
}
