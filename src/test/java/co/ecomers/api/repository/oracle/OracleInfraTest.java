package co.ecomers.api.repository.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

class OracleInfraTest {

    @Nested
    class Transacciones {

        static final String SET = "{ call DBMS_SESSION.SET_IDENTIFIER(?) }";
        static final String CLEAR = "{ call DBMS_SESSION.CLEAR_IDENTIFIER }";

        DataSource ds = mock(DataSource.class);
        Connection con = mock(Connection.class);
        CallableStatement cs = mock(CallableStatement.class);
        CallableStatement csId = mock(CallableStatement.class);
        BaseDatosCliente db;

        @BeforeEach
        void preparar() throws Exception {
            when(ds.getConnection()).thenReturn(con);
            when(con.prepareCall(anyString())).thenReturn(cs);
            when(con.prepareCall(SET)).thenReturn(csId);
            when(con.prepareCall(CLEAR)).thenReturn(csId);
            db = new BaseDatosCliente(ds, new DataSourceTransactionManager(ds), new ReintentoOracle(ms -> { }));
        }

        @Test
        void fijaIdentificadorEjecutaYConfirma() throws Exception {
            int r = db.enTransaccion("kc:abc", s -> 42);

            assertThat(r).isEqualTo(42);
            InOrder orden = inOrder(con, csId);
            orden.verify(con).prepareCall(SET);
            orden.verify(csId).setString(1, "kc:abc");
            orden.verify(con).prepareCall(CLEAR);
            orden.verify(con).commit();
            verify(con, never()).rollback();
        }

        @Test
        void errorHaceRollbackYLimpiaElIdentificador() throws Exception {
            assertThatThrownBy(() -> db.enTransaccion("kc:abc", s -> {
                throw new IllegalStateException("falla");
            })).isInstanceOf(IllegalStateException.class);

            verify(con).rollback();
            verify(con, never()).commit();
            verify(con).prepareCall(CLEAR);
        }

        @Test
        void lecturaSiempreHaceRollback() throws Exception {
            db.lectura("anonimo", s -> "x");
            verify(con).rollback();
            verify(con, never()).commit();
        }

        @Test
        void recortaElUsuarioA64Caracteres() throws Exception {
            db.enTransaccion("u".repeat(100), s -> 1);
            verify(csId).setString(1, "u".repeat(64));
        }

        @Test
        void procedimientoArmaLaLlamadaYTraduceErrores() throws Exception {
            db.enTransaccionSinResultado("u", s -> s.procedimiento("ECOMERS.PKG_CARRITO.agregar_item", 5L, 9L, 2));
            verify(con).prepareCall("{ call ECOMERS.PKG_CARRITO.agregar_item(?, ?, ?) }");
            verify(cs).setObject(1, 5L);
            verify(cs).setObject(3, 2);

            when(cs.execute()).thenThrow(new SQLException("ORA-20101: Variante inexistente", "72000", 20101));
            assertThatThrownBy(() -> db.enTransaccionSinResultado("u",
                    s -> s.procedimiento("ECOMERS.PKG_CARRITO.agregar_item", 5L, 9L, 2)))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VARIANTE_NO_DISPONIBLE);
        }

        @Test
        void textoLargoComoClobSeEnviaComoStreamNoComoObjeto() throws Exception {
            String json = "{\"evento\":\"x\"}";
            db.enTransaccionSinResultado("u", s -> s.procedimiento("ECOMERS.PKG_X.y",
                    new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.CLOB, json),
                    new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.CLOB, null)));

            verify(cs).setCharacterStream(org.mockito.ArgumentMatchers.eq(1),
                    org.mockito.ArgumentMatchers.any(java.io.Reader.class), org.mockito.ArgumentMatchers.eq((long) json.length()));
            verify(cs).setNull(2, java.sql.Types.CLOB);
            verify(cs, never()).setObject(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(java.sql.Types.CLOB));
        }

        @Test
        void errorTransitorioReintentaTodaLaTransaccion() throws Exception {
            var intentos = new AtomicInteger();
            String r = db.enTransaccion("u", s -> {
                if (intentos.incrementAndGet() < 3) {
                    throw new NegocioException(CodigoError.RECURSO_OCUPADO, "ocupado", 20168);
                }
                return "ok";
            });
            assertThat(r).isEqualTo("ok");
            assertThat(intentos).hasValue(3);
            verify(con, org.mockito.Mockito.times(2)).rollback();
        }
    }

    @Nested
    class Reintentos {

        private final List<Long> esperas = new ArrayList<>();
        private final ReintentoOracle reintento = new ReintentoOracle(esperas::add);

        @Test
        void reintentaTransitorioConEsperasCrecientes() {
            var llamadas = new AtomicInteger();
            String r = reintento.ejecutar(() -> {
                if (llamadas.incrementAndGet() < 3) {
                    throw new NegocioException(CodigoError.RECURSO_OCUPADO, "ocupado", 20144);
                }
                return "ok";
            });
            assertThat(r).isEqualTo("ok");
            assertThat(esperas).containsExactly(100L, 300L);
        }

        @Test
        void noReintentaLoQueNoEsTransitorio() {
            var llamadas = new AtomicInteger();
            assertThatThrownBy(() -> reintento.ejecutar(() -> {
                llamadas.incrementAndGet();
                throw new NegocioException(CodigoError.STOCK_INSUFICIENTE, "sin stock", 20122);
            })).isInstanceOf(NegocioException.class);
            assertThat(llamadas).hasValue(1);
        }

        @Test
        void agotaLosTresIntentosYPropaga() {
            var llamadas = new AtomicInteger();
            assertThatThrownBy(() -> reintento.ejecutar(() -> {
                llamadas.incrementAndGet();
                throw new NegocioException(CodigoError.RECURSO_OCUPADO, "ocupado", 20188);
            })).isInstanceOf(NegocioException.class);
            assertThat(llamadas).hasValue(3);
        }
    }

    @Nested
    class Traductor {

        private RuntimeException envuelta(int codigo, String mensaje) {
            return new UncategorizedSQLException("call", "CALL", new SQLException(mensaje, "72000", codigo));
        }

        @Test
        void traduceStockInsuficienteConMensajeLimpio() {
            var traducida = TraductorErroresOracle.traducir(envuelta(20122,
                    "ORA-20122: Stock insuficiente para SKU-1\nORA-06512: en \"ECOMERS.PKG_RESERVA\", línea 40"));

            assertThat(traducida).isInstanceOf(NegocioException.class);
            var n = (NegocioException) traducida;
            assertThat(n.codigo()).isEqualTo(CodigoError.STOCK_INSUFICIENTE);
            assertThat(n.codigo().estado()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(n.getMessage()).isEqualTo("Stock insuficiente para SKU-1");
            assertThat(n.codigoOracle()).isEqualTo(20122);
        }

        @Test
        void erroresDeInventarioTienenSuCodigo() {
            assertThat(((NegocioException) TraductorErroresOracle.traducir(envuelta(20120, "ORA-20120: x"))).codigo())
                    .isEqualTo(CodigoError.VARIANTE_SIN_INVENTARIO);
            assertThat(((NegocioException) TraductorErroresOracle.traducir(envuelta(20123, "ORA-20123: x"))).codigo())
                    .isEqualTo(CodigoError.AJUSTE_INVALIDO);
            assertThat(((NegocioException) TraductorErroresOracle.traducir(envuelta(20125, "ORA-20125: x"))).codigo())
                    .isEqualTo(CodigoError.OBSERVACION_REQUERIDA);
        }

        @Test
        void codigoDesconocidoOSinSqlNoSeTraduce() {
            var origen = envuelta(1400, "ORA-01400: no se puede insertar NULL");
            assertThat(TraductorErroresOracle.traducir(origen)).isSameAs(origen);
            var otra = new IllegalStateException("x");
            assertThat(TraductorErroresOracle.traducir(otra)).isSameAs(otra);
        }

        @Test
        void codigoOracleBuscaEnLaCadenaDeCausas() {
            var e = new RuntimeException("a", new RuntimeException("b", new SQLException("m", "s", 20144)));
            assertThat(TraductorErroresOracle.codigoOracle(e)).contains(20144);
        }
    }
}
