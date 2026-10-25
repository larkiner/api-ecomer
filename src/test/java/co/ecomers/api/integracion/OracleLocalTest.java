package co.ecomers.api.integracion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.Tokens;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.model.NotificacionPago;
import co.ecomers.api.repository.backoffice.InventarioRepository;
import co.ecomers.api.repository.backoffice.ReporteRepository;
import co.ecomers.api.repository.integracion.ColaRepository;
import co.ecomers.api.repository.integracion.NotificacionPagoRepository;
import co.ecomers.api.repository.integracion.PedidoNotificacionRepository;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import co.ecomers.api.repository.oracle.BaseDatosIntegracion;
import co.ecomers.api.repository.tienda.CarritoRepository;
import co.ecomers.api.repository.tienda.CatalogoRepository;
import co.ecomers.api.repository.tienda.PagoRepository;
import co.ecomers.api.repository.tienda.PedidoRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pruebas contra el Oracle local (./gradlew integrationTest). Usan las claves de secrets.properties.
 *
 * <p>NO modifican datos: solo leen, o llaman a los paquetes con IDs inexistentes, que validan y
 * fallan antes de escribir (y la transaccion se deshace igual). Al final se comprueba que la
 * cantidad de pedidos no cambio. Los consumidores de la cola quedan apagados para no tomar eventos reales.
 */
@Tag("oracle")
@SpringBootTest(properties = "app.cola.habilitada=false")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleLocalTest {

    private static final ClienteActual NADIE = new ClienteActual(-1L, "prueba-integracion");
    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "prueba-integracion");

    @Autowired MockMvc mvc;
    @Autowired BaseDatosCliente dbCliente;
    @Autowired BaseDatosIntegracion dbIntegracion;
    @Autowired CatalogoRepository catalogo;
    @Autowired CarritoRepository carritos;
    @Autowired PedidoRepository pedidos;
    @Autowired PagoRepository pagos;
    @Autowired InventarioRepository inventario;
    @Autowired ReporteRepository reportes;
    @Autowired NotificacionPagoRepository notificaciones;
    @Autowired ColaRepository cola;
    @Autowired PedidoNotificacionRepository pedidosParaNotificar;

    private long pedidosAntes;

    private long contarPedidos() {
        return dbIntegracion.lectura("prueba-integracion",
                s -> s.sql().sql("SELECT COUNT(*) FROM ECOMERS.PEDIDO").query(Long.class).single());
    }

    @BeforeAll
    void contarAntes() {
        pedidosAntes = contarPedidos();
    }

    @AfterAll
    void nadaCambio() {
        assertThat(contarPedidos()).as("las pruebas no deben crear pedidos").isEqualTo(pedidosAntes);
    }

    private static void fallaCon(CodigoError esperado, int oracle, Executable llamada) {
        assertThatThrownBy(llamada::execute)
                .isInstanceOf(NegocioException.class)
                .satisfies(e -> {
                    assertThat(((NegocioException) e).codigo()).isEqualTo(esperado);
                    assertThat(((NegocioException) e).codigoOracle()).isEqualTo(oracle);
                });
    }

    @Test
    void lasTresConexionesEstanArriba() throws Exception {
        mvc.perform(get("/actuator/health").with(Tokens.empleado()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.components.dataSourceCliente.status").value("UP"))
                .andExpect(jsonPath("$.components.db.components.dataSourceEmpleado.status").value("UP"))
                .andExpect(jsonPath("$.components.db.components.dataSourceIntegracion.status").value("UP"));
    }

    @Test
    void elIdentificadorDeSesionLlegaAOracle() {
        String id = dbCliente.lectura("kc:prueba-integracion", s -> s.sql()
                .sql("SELECT SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER') FROM dual").query(String.class).single());
        assertThat(id).isEqualTo("kc:prueba-integracion");
    }

    @Test
    void catalogoConsultaLaVista() throws Exception {
        assertThat(catalogo.contar(null, null)).isGreaterThanOrEqualTo(0);
        assertThat(catalogo.listar(null, null, 0, 5)).hasSizeLessThanOrEqualTo(5);
        assertThat(catalogo.listar("portatil_100%", null, 0, 5)).isNotNull();
        assertThat(catalogo.contar(null, -1L)).isZero();
        assertThat(catalogo.listarCategorias()).isNotNull();
        assertThat(catalogo.buscar(-1L)).isEmpty();
        mvc.perform(get("/api/v1/catalogo/variantes?size=5")).andExpect(status().isOk());
    }

    @Test
    void cadaReporteConsultaSuVista() {
        LocalDate hoy = LocalDate.now();
        assertThat(reportes.stockBajo(EMPLEADO, 5)).isNotNull();
        assertThat(reportes.conciliacionStock(EMPLEADO, 5)).isNotNull();
        assertThat(reportes.pedidosPendientes(EMPLEADO, 5)).isNotNull();
        assertThat(reportes.ventasDiarias(EMPLEADO, hoy.minusDays(30), hoy)).isNotNull();
        assertThat(reportes.ventasProducto(EMPLEADO, hoy.minusDays(30), hoy, 5)).isNotNull();
        assertThat(reportes.topClientes(EMPLEADO, 5)).isNotNull();
        assertThat(reportes.conciliacionPagos(EMPLEADO, 5)).isNotNull();
        assertThat(reportes.carritosAbandonados(EMPLEADO, 5)).isNotNull();
        assertThat(reportes.colaPendiente(EMPLEADO, 5)).isNotNull();
    }

    @Test
    void paquetesDeLaTiendaValidanAlClienteSinEscribir() {
        // agregar_item valida primero que la variante tenga precio vigente; vaciar valida primero al cliente.
        fallaCon(CodigoError.VARIANTE_NO_DISPONIBLE, 20101, () -> carritos.agregarItem(NADIE, -1L, 1));
        fallaCon(CodigoError.CLIENTE_INACTIVO, 20140, () -> carritos.vaciar(NADIE));
        fallaCon(CodigoError.CLIENTE_INACTIVO, 20161, () -> pedidos.crear(NADIE, null));
        fallaCon(CodigoError.PEDIDO_NO_ENCONTRADO, 20160, () -> pedidos.cancelar(NADIE, -1L, null));
        fallaCon(CodigoError.PEDIDO_NO_ENCONTRADO, 20160, () -> pagos.iniciar(NADIE, -1L, "WOMPI"));
    }

    @Test
    void inventarioValidaAntesDeEscribir() {
        fallaCon(CodigoError.VARIANTE_SIN_INVENTARIO, 20120, () -> inventario.entrada(EMPLEADO, -1L, 1, null));
        fallaCon(CodigoError.CANTIDAD_INVALIDA, 20121, () -> inventario.ajuste(EMPLEADO, -1L, 0, "x"));
    }

    @Test
    void integracionValidaAntesDeEscribir() {
        var notificacion = new NotificacionPago("WOMPI", "0".repeat(32), "prueba-tx", "prueba-evento", "PENDIENTE",
                null, null, "{}");
        fallaCon(CodigoError.PAGO_NO_ENCONTRADO, 20180, () -> notificaciones.registrar(notificacion));
        fallaCon(CodigoError.ERROR_INTERNO, 20200, () -> cola.tomarLote("TIPO_QUE_NO_EXISTE", 1));
        assertThat(pedidosParaNotificar.buscar(-1L)).isEmpty();
    }
}
