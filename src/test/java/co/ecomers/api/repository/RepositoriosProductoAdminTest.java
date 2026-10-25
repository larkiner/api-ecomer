package co.ecomers.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.backoffice.ProductoCreadoResponse;
import co.ecomers.api.dto.backoffice.ProductoEdicionRequest;
import co.ecomers.api.dto.backoffice.ProductoRequest;
import co.ecomers.api.dto.backoffice.ProductoResponse;
import co.ecomers.api.dto.backoffice.VarianteEdicionRequest;
import co.ecomers.api.dto.backoffice.VarianteRequest;
import co.ecomers.api.dto.backoffice.VarianteResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.CatalogoAdminRepository;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;

/** Productos y variantes: cada metodo llama a PKG_CATALOGO con el ID del empleado primero. */
class RepositoriosProductoAdminTest {

    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "ana.bodega@ecomers.co");
    private static final String PKG = "ECOMERS.PKG_CATALOGO.";
    private static final String IMAGEN = "https://abc.supabase.co/storage/v1/object/public/productos/productos/lap.png";

    private final DbFalsa<BaseDatosEmpleado> f = DbFalsa.de(BaseDatosEmpleado.class);
    private final CatalogoAdminRepository repo = new CatalogoAdminRepository(f.db);

    private static VarianteRequest variante(String sku, String precio, boolean controla) {
        return new VarianteRequest(sku, "Variante " + sku, new BigDecimal(precio), controla);
    }

    // ---------------- Crear un producto con variantes: una sola transaccion ----------------

    @Test
    void crearUnProductoConVariantesEsUnaSolaTransaccionEnOrden() {
        when(f.sesion.funcionNumero(eq(PKG + "crear_producto"), any(), any(), any(), any(), any())).thenReturn(50L);
        when(f.sesion.funcionNumero(eq(PKG + "agregar_variante"), any(), any(), any(), any(), any(), any()))
                .thenReturn(100L, 101L);
        var req = new ProductoRequest(1L, 2L, "Portatil X", "Descripcion", null,
                List.of(variante("LAP-001", "2500000.00", true), variante("LAP-002", "3000000.00", false)));

        ProductoCreadoResponse creado = repo.crearProductoConVariantes(EMPLEADO, req);

        assertThat(creado).isEqualTo(new ProductoCreadoResponse(50L, List.of(100L, 101L)));
        verify(f.db, times(1)).enTransaccion(eq("emp:ana.bodega@ecomers.co"), any());
        var orden = inOrder(f.sesion);
        orden.verify(f.sesion).funcionNumero(PKG + "crear_producto", 3L, 1L, 2L, "Portatil X", "Descripcion");
        orden.verify(f.sesion).funcionNumero(PKG + "agregar_variante", 3L, 50L, "LAP-001", "Variante LAP-001",
                new BigDecimal("2500000.00"), 1);
        orden.verify(f.sesion).funcionNumero(PKG + "agregar_variante", 3L, 50L, "LAP-002", "Variante LAP-002",
                new BigDecimal("3000000.00"), 0);
    }

    @Test
    void siUnaVarianteFallaLaExcepcionSalePorLaTransaccionYNoSeIntentanLasDemas() {
        when(f.sesion.funcionNumero(eq(PKG + "crear_producto"), any(), any(), any(), any(), any())).thenReturn(50L);
        when(f.sesion.funcionNumero(eq(PKG + "agregar_variante"), any(), any(), any(), any(), any(), any()))
                .thenReturn(100L)
                .thenThrow(new NegocioException(CodigoError.SKU_REPETIDO, "Ya existe una variante con el SKU X", 20335));
        var req = new ProductoRequest(1L, 2L, "Portatil X", null, null, List.of(
                variante("LAP-001", "10", false), variante("LAP-001", "10", false), variante("LAP-003", "10", false)));

        assertThatThrownBy(() -> repo.crearProductoConVariantes(EMPLEADO, req))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.SKU_REPETIDO);

        verify(f.sesion, times(2)).funcionNumero(eq(PKG + "agregar_variante"), any(), any(), any(), any(), any(), any());
        verify(f.sesion, never()).funcionNumero(PKG + "agregar_variante", 3L, 50L, "LAP-003", "Variante LAP-003",
                new BigDecimal("10"), 0);
        verify(f.db, times(1)).enTransaccion(any(), any());
    }

    // ---------------- Imagen del producto ----------------

    @Test
    void conImagenSeFijaDentroDeLaMismaTransaccionTrasCrearElProductoYAntesDeLasVariantes() {
        when(f.sesion.funcionNumero(eq(PKG + "crear_producto"), any(), any(), any(), any(), any())).thenReturn(50L);
        when(f.sesion.funcionNumero(eq(PKG + "agregar_variante"), any(), any(), any(), any(), any(), any())).thenReturn(100L);
        var req = new ProductoRequest(1L, 2L, "Portatil X", null, IMAGEN, List.of(variante("LAP-001", "10", true)));

        repo.crearProductoConVariantes(EMPLEADO, req);

        verify(f.db, times(1)).enTransaccion(any(), any());
        var orden = inOrder(f.sesion);
        orden.verify(f.sesion).funcionNumero(eq(PKG + "crear_producto"), any(), any(), any(), any(), any());
        orden.verify(f.sesion).procedimiento(PKG + "fijar_imagen", 3L, 50L, IMAGEN);
        orden.verify(f.sesion).funcionNumero(eq(PKG + "agregar_variante"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sinImagenNoSeLlamaAFijarImagenAlCrear() {
        when(f.sesion.funcionNumero(eq(PKG + "crear_producto"), any(), any(), any(), any(), any())).thenReturn(50L);
        when(f.sesion.funcionNumero(eq(PKG + "agregar_variante"), any(), any(), any(), any(), any(), any())).thenReturn(100L);

        repo.crearProductoConVariantes(EMPLEADO,
                new ProductoRequest(1L, 2L, "Portatil X", null, null, List.of(variante("LAP-001", "10", true))));

        verify(f.sesion, never()).procedimiento(eq(PKG + "fijar_imagen"), any(), any(), any());
    }

    @Test
    void editarUnProductoReemplazaSuImagenYNuloLaQuita() {
        repo.actualizarProducto(EMPLEADO, 50L, new ProductoEdicionRequest(1L, 2L, "Portatil Y", null, IMAGEN));
        repo.actualizarProducto(EMPLEADO, 51L, new ProductoEdicionRequest(1L, 2L, "Otro", null, null));

        verify(f.db, times(2)).enTransaccionSinResultado(eq("emp:ana.bodega@ecomers.co"), any(Consumer.class));
        verify(f.sesion).procedimiento(PKG + "fijar_imagen", 3L, 50L, IMAGEN);
        verify(f.sesion).procedimiento(PKG + "fijar_imagen", 3L, 51L, null);
    }

    // ---------------- Variantes y ediciones ----------------

    @Test
    void agregarVarianteDevuelveElIdYPasaElControlDeStockComoUnoOCero() {
        when(f.sesion.funcionNumero(eq(PKG + "agregar_variante"), any(), any(), any(), any(), any(), any())).thenReturn(7L);

        assertThat(repo.agregarVariante(EMPLEADO, 50L, variante("MOU-001", "45000.50", false))).isEqualTo(7L);

        verify(f.sesion).funcionNumero(PKG + "agregar_variante", 3L, 50L, "MOU-001", "Variante MOU-001",
                new BigDecimal("45000.50"), 0);
    }

    @Test
    void edicionesYEstadosLlamanASusProcedimientos() {
        repo.actualizarProducto(EMPLEADO, 50L, new ProductoEdicionRequest(1L, 2L, "Portatil Y", null, null));
        repo.cambiarEstadoProducto(EMPLEADO, 50L, false);
        repo.actualizarVariante(EMPLEADO, 7L, new VarianteEdicionRequest("Mouse v2", new BigDecimal("50000.00")));
        repo.cambiarEstadoVariante(EMPLEADO, 7L, true);
        // (actualizarProducto fija ademas la imagen en su propia transaccion: ver las pruebas de imagen)

        verify(f.db, times(4)).enTransaccionSinResultado(eq("emp:ana.bodega@ecomers.co"), any(Consumer.class));
        verify(f.sesion).procedimiento(PKG + "actualizar_producto", 3L, 50L, 1L, 2L, "Portatil Y", null);
        verify(f.sesion).procedimiento(PKG + "cambiar_estado_producto", 3L, 50L, 0);
        verify(f.sesion).procedimiento(PKG + "actualizar_variante", 3L, 7L, "Mouse v2", new BigDecimal("50000.00"));
        verify(f.sesion).procedimiento(PKG + "cambiar_estado_variante", 3L, 7L, 1);
    }

    // ---------------- Listado: una fila por variante, agrupado por producto ----------------

    @Test
    @SuppressWarnings("unchecked")
    void listarProductosAgrupaLasFilasPorProductoYConservaLasInactivas() throws Exception {
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq(PKG + "listar_productos"), mapper.capture(), eq(3L), eq(0), eq(20)))
                .thenAnswer(i -> List.of(
                        mapper.getValue().mapRow(fila(50L, "Portatil X", 1, 100L, "LAP-001", "2500000.00", 1, 1), 0),
                        mapper.getValue().mapRow(fila(50L, "Portatil X", 1, 101L, "LAP-002", "3000000.00", 0, 0), 1),
                        mapper.getValue().mapRow(fila(51L, "Producto sin variantes", 0, null, null, null, 0, 0), 2)));

        List<ProductoResponse> lista = repo.listarProductos(EMPLEADO, 0, 20);

        assertThat(lista).hasSize(2);
        assertThat(lista.get(0)).isEqualTo(new ProductoResponse(50L, 1L, 2L, "Portatil X", "desc", IMAGEN, true, List.of(
                new VarianteResponse(100L, "LAP-001", "Variante LAP-001", new BigDecimal("2500000.00"), true, true),
                new VarianteResponse(101L, "LAP-002", "Variante LAP-002", new BigDecimal("3000000.00"), false, false))));
        assertThat(lista.get(1).activo()).isFalse();
        assertThat(lista.get(1).variantes()).isEmpty();
        verify(f.db).lectura(eq("emp:ana.bodega@ecomers.co"), any());
    }

    private static ResultSet fila(long idProducto, String producto, int productoActivo, Long idVariante, String sku,
                                  String precio, int controla, int varianteActiva) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("ID_PRODUCTO")).thenReturn(idProducto);
        when(rs.getLong("ID_CATEGORIA")).thenReturn(1L);
        when(rs.getLong("ID_IMPUESTO")).thenReturn(2L);
        when(rs.getString("PRODUCTO")).thenReturn(producto);
        when(rs.getString("DESCRIPCION")).thenReturn("desc");
        when(rs.getString("IMAGEN_URL")).thenReturn(idProducto == 50L ? IMAGEN : null);
        when(rs.getInt("PRODUCTO_ACTIVO")).thenReturn(productoActivo);
        when(rs.getLong("ID_VARIANTE")).thenReturn(idVariante == null ? 0L : idVariante);
        when(rs.wasNull()).thenReturn(idVariante == null);
        when(rs.getString("SKU")).thenReturn(sku);
        when(rs.getString("VARIANTE")).thenReturn(sku == null ? null : "Variante " + sku);
        when(rs.getBigDecimal("PRECIO_BASE")).thenReturn(precio == null ? null : new BigDecimal(precio));
        when(rs.getInt("CONTROLA_STOCK")).thenReturn(controla);
        when(rs.getInt("VARIANTE_ACTIVA")).thenReturn(varianteActiva);
        return rs;
    }
}
