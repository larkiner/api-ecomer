package co.ecomers.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.model.EventoCola;
import co.ecomers.api.model.IntentoPago;
import co.ecomers.api.model.NotificacionPago;
import co.ecomers.api.repository.backoffice.InventarioRepository;
import co.ecomers.api.repository.integracion.ColaRepository;
import co.ecomers.api.repository.integracion.NotificacionPagoRepository;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import co.ecomers.api.repository.oracle.BaseDatosIntegracion;
import co.ecomers.api.repository.tienda.CarritoRepository;
import co.ecomers.api.repository.tienda.PagoRepository;
import co.ecomers.api.repository.tienda.PedidoRepository;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Types;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.CallableStatementCallback;
import org.springframework.jdbc.core.SqlParameterValue;

/** Cada repositorio llama al paquete correcto, con los argumentos en orden y el usuario final correcto. */
class RepositoriosTest {

    private static final ClienteActual CLIENTE = new ClienteActual(7L, "ana@correo.com");
    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "ana");

    // ---------------- Tienda (ECOMERS_CLIENTE_APP) ----------------

    @Test
    void carritoLlamaAPkgCarritoConElClienteAutenticado() {
        var f = DbFalsa.de(BaseDatosCliente.class);
        var repo = new CarritoRepository(f.db);

        repo.agregarItem(CLIENTE, 5L, 2);
        repo.cambiarCantidad(CLIENTE, 5L, 9);
        repo.quitarItem(CLIENTE, 5L);
        repo.vaciar(CLIENTE);

        verify(f.db, org.mockito.Mockito.times(4)).enTransaccionSinResultado(eq("cli:7"), any(Consumer.class));
        verify(f.sesion).procedimiento("ECOMERS.PKG_CARRITO.agregar_item", 7L, 5L, 2);
        verify(f.sesion).procedimiento("ECOMERS.PKG_CARRITO.cambiar_cantidad", 7L, 5L, 9);
        verify(f.sesion).procedimiento("ECOMERS.PKG_CARRITO.quitar_item", 7L, 5L);
        verify(f.sesion).procedimiento("ECOMERS.PKG_CARRITO.vaciar", 7L);
    }

    @Test
    void crearPedidoPasaLaDireccionComoNumeroONulo() {
        var f = DbFalsa.de(BaseDatosCliente.class);
        var repo = new PedidoRepository(f.db);
        when(f.sesion.funcionNumero(eq("ECOMERS.PKG_PEDIDO.crear_desde_carrito"), any(), any())).thenReturn(1001L);

        assertThat(repo.crear(CLIENTE, null)).isEqualTo(1001L);
        repo.crear(CLIENTE, 5L);

        var direccion = ArgumentCaptor.forClass(Object.class);
        verify(f.sesion, org.mockito.Mockito.times(2))
                .funcionNumero(eq("ECOMERS.PKG_PEDIDO.crear_desde_carrito"), eq(7L), direccion.capture());
        assertThat(direccion.getAllValues()).extracting(v -> ((SqlParameterValue) v).getValue()).containsExactly(null, 5L);
        assertThat(direccion.getAllValues()).extracting(v -> ((SqlParameterValue) v).getSqlType())
                .containsOnly(Types.NUMERIC);
    }

    @Test
    void cancelarPedido() {
        var f = DbFalsa.de(BaseDatosCliente.class);
        new PedidoRepository(f.db).cancelar(CLIENTE, 10L, "ya no");
        verify(f.sesion).procedimiento("ECOMERS.PKG_PEDIDO.cancelar", 7L, 10L, "ya no");
    }

    @Test
    @SuppressWarnings("unchecked")
    void iniciarPagoLeeLosParametrosDeSalida() throws Exception {
        var f = DbFalsa.de(BaseDatosCliente.class);
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getLong(4)).thenReturn(55L);
        when(cs.getString(5)).thenReturn("A1B2C3D4E5F60718293A4B5C6D7E8F90");
        when(cs.getBigDecimal(6)).thenReturn(new BigDecimal("238000"));
        when(f.sesion.llamada(any(), any())).thenAnswer(i ->
                ((CallableStatementCallback<Object>) i.getArgument(1)).doInCallableStatement(cs));

        IntentoPago intento = new PagoRepository(f.db).iniciar(CLIENTE, 10L, "WOMPI");

        verify(f.sesion).llamada(eq("{ call ECOMERS.PKG_PEDIDO.iniciar_pago(?, ?, ?, ?, ?, ?) }"), any());
        verify(cs).setLong(1, 7L);
        verify(cs).setLong(2, 10L);
        verify(cs).setString(3, "WOMPI");
        verify(cs).registerOutParameter(4, Types.NUMERIC);
        verify(cs).registerOutParameter(5, Types.VARCHAR);
        verify(cs).registerOutParameter(6, Types.NUMERIC);
        assertThat(intento).isEqualTo(new IntentoPago(55L, "A1B2C3D4E5F60718293A4B5C6D7E8F90", new BigDecimal("238000")));
    }

    // ---------------- Backoffice (ECOMERS_EMPLEADO_APP) ----------------

    @Test
    void inventarioLlamaAPkgInventarioConElEmpleado() {
        var f = DbFalsa.de(BaseDatosEmpleado.class);
        var repo = new InventarioRepository(f.db);

        repo.entrada(EMPLEADO, 5L, 10, null);
        repo.ajuste(EMPLEADO, 5L, -2, "Merma");

        verify(f.db, org.mockito.Mockito.times(2)).enTransaccionSinResultado(eq("emp:ana"), any(Consumer.class));
        verify(f.sesion).procedimiento("ECOMERS.PKG_INVENTARIO.entrada", 5L, 10, null);
        verify(f.sesion).procedimiento("ECOMERS.PKG_INVENTARIO.ajuste", 5L, -2, "Merma");
    }

    // ---------------- Integracion (ECOMERS_INTEGRACION_APP) ----------------

    @Test
    void notificacionDePagoVaComoClob() {
        var f = DbFalsa.de(BaseDatosIntegracion.class);
        var n = new NotificacionPago("WOMPI", "A1B2", "1234-1", "chk", "APROBADO", new BigDecimal("238000.00"), "PSE", "{\"a\":1}");
        when(f.sesion.funcionNumero(eq("ECOMERS.PKG_PAGO.registrar_notificacion"),
                any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(5L);

        assertThat(new NotificacionPagoRepository(f.db).registrar(n)).isEqualTo(5L);

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(f.sesion).funcionNumero(eq("ECOMERS.PKG_PAGO.registrar_notificacion"), eq("WOMPI"), eq("A1B2"),
                eq("1234-1"), eq("chk"), eq("APROBADO"), eq(new BigDecimal("238000.00")), eq("PSE"), payload.capture());
        assertThat(((SqlParameterValue) payload.getValue()).getSqlType()).isEqualTo(Types.CLOB);
        verify(f.db).enTransaccion(eq("webhook:wompi"), any());
    }

    @Test
    void colaTomaCompletaYFalla() {
        var f = DbFalsa.de(BaseDatosIntegracion.class);
        var repo = new ColaRepository(f.db);
        var evento = new EventoCola(1L, "ENVIAR_CORREO", "c", "{}", 0);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_COLA.tomar_lote"), any(), eq("ENVIAR_CORREO"), eq(10)))
                .thenReturn(List.of(evento));

        assertThat(repo.tomarLote("ENVIAR_CORREO", 10)).containsExactly(evento);
        repo.completar(5L, "ENVIAR_CORREO");
        repo.completarErp(6L, 45L, "ERP-45");
        repo.fallar(7L, "ENVIAR_CORREO", "x".repeat(5000));

        verify(f.sesion).procedimiento("ECOMERS.PKG_COLA.completar", 5L);
        verify(f.sesion).procedimiento("ECOMERS.PKG_COLA.completar_erp", 6L, 45L, "ERP-45");
        var texto = ArgumentCaptor.forClass(Object.class);
        verify(f.sesion).procedimiento(eq("ECOMERS.PKG_COLA.fallar"), eq(7L), texto.capture());
        assertThat((String) texto.getValue()).hasSize(4000);
    }

    @Test
    void colaIgnoraEventoYaResueltoPeroNoOtrosErrores() {
        var f = DbFalsa.de(BaseDatosIntegracion.class);
        var repo = new ColaRepository(f.db);
        doThrow(new NegocioException(CodigoError.EVENTO_NO_PROCESANDO, "ya no", 20204))
                .when(f.sesion).procedimiento("ECOMERS.PKG_COLA.completar", 5L);
        doThrow(new IllegalStateException("x")).when(f.sesion).procedimiento("ECOMERS.PKG_COLA.completar", 6L);

        assertThatCode(() -> repo.completar(5L, "ENVIAR_CORREO")).doesNotThrowAnyException();
        assertThatThrownBy(() -> repo.completar(6L, "ENVIAR_CORREO")).isInstanceOf(IllegalStateException.class);
    }

    // ---------------- Catalogo ----------------

    @Test
    void patronDeBusquedaEscapaComodines() throws Exception {
        var patron = co.ecomers.api.repository.tienda.CatalogoRepository.class.getDeclaredMethod("patron", String.class);
        patron.setAccessible(true);
        assertThat(patron.invoke(null, "porta")).isEqualTo("%PORTA%");
        assertThat(patron.invoke(null, "50%_x\\")).isEqualTo("%50\\%\\_X\\\\%");
        assertThat(patron.invoke(null, "  mouse ")).isEqualTo("%MOUSE%");
    }

    @Test
    void elFiltroDelCatalogoSoloUsaParametrosYSoloAgregaLoQueSePide() throws Exception {
        var donde = co.ecomers.api.repository.tienda.CatalogoRepository.class.getDeclaredMethod("donde", String.class, Long.class);
        donde.setAccessible(true);

        assertThat((String) donde.invoke(null, null, null)).isEmpty();
        assertThat((String) donde.invoke(null, "lap", null)).contains(":patron").doesNotContain(":categoria");
        assertThat((String) donde.invoke(null, null, 30L)).contains(":categoria").contains("V_CATEGORIA_PUBLICA")
                .contains("CONNECT BY PRIOR ID_CATEGORIA = ID_PADRE").doesNotContain(":patron");
        assertThat((String) donde.invoke(null, "lap", 30L)).contains(":patron").contains(":categoria").contains(" AND ")
                .doesNotContain("30").doesNotContain("lap");
    }
}
