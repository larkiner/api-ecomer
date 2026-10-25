package co.ecomers.api.controller.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.Tokens;
import co.ecomers.api.dto.backoffice.ImpuestoResponse;
import co.ecomers.api.dto.backoffice.ProductoCreadoResponse;
import co.ecomers.api.dto.backoffice.ProductoEdicionRequest;
import co.ecomers.api.dto.backoffice.ProductoRequest;
import co.ecomers.api.dto.backoffice.ProductoResponse;
import co.ecomers.api.dto.backoffice.VarianteCreadaResponse;
import co.ecomers.api.dto.backoffice.VarianteEdicionRequest;
import co.ecomers.api.dto.backoffice.VarianteResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.CatalogoAdminService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@PruebaWeb(ProductoController.class)
class ProductoControllerTest {

    private static final String BASE = "/api/v1/backoffice";
    private static final String PRODUCTO = "{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"Portatil X\",\"descripcion\":\"desc\","
            + "\"variantes\":[{\"sku\":\"LAP-001\",\"nombre\":\"16GB\",\"precioBase\":2500000.00,\"controlaStock\":true}]}";
    private static final String VARIANTE = "{\"sku\":\"LAP-002\",\"nombre\":\"32GB\",\"precioBase\":3000000,\"controlaStock\":false}";
    private static final EmpleadoActual TRABAJADOR = new EmpleadoActual(Tokens.ID_EMPLEADO, "ana.bodega@ecomers.co");
    private static final EmpleadoActual ADMIN = new EmpleadoActual(Tokens.ID_ADMIN, "admin@ecomers.co");

    @Autowired MockMvc mvc;
    @MockitoBean CatalogoAdminService servicio;

    private static String producto(String variantes) {
        return "{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"Portatil X\",\"variantes\":" + variantes + "}";
    }

    private static String varianteConPrecio(String precio) {
        return "[{\"sku\":\"LAP-001\",\"nombre\":\"16GB\",\"precioBase\":" + precio + ",\"controlaStock\":true}]";
    }

    // ---------------- Lecturas: cualquier empleado ----------------

    @Test
    void cualquierEmpleadoListaImpuestosYProductosYElDineroViajaComoTexto() throws Exception {
        when(servicio.listarImpuestos(TRABAJADOR)).thenReturn(List.of(new ImpuestoResponse(1L, "IVA 19%", new BigDecimal("0.1900"), true)));
        when(servicio.listarProductos(TRABAJADOR, 0, 20)).thenReturn(List.of(new ProductoResponse(50L, 1L, 2L, "Portatil X", null, "https://abc.supabase.co/storage/v1/object/public/productos/productos/lap.png", true,
                List.of(new VarianteResponse(100L, "LAP-001", "16GB", new BigDecimal("2500000.00"), true, false)))));

        mvc.perform(get(BASE + "/impuestos").with(Tokens.trabajador()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tasa").value("0.1900"));
        mvc.perform(get(BASE + "/productos").with(Tokens.trabajador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].imagenUrl").value("https://abc.supabase.co/storage/v1/object/public/productos/productos/lap.png"))
                .andExpect(jsonPath("$[0].variantes[0].precioBase").value("2500000.00"))
                .andExpect(jsonPath("$[0].variantes[0].activa").value(false));
        mvc.perform(get(BASE + "/productos").with(Tokens.cliente())).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/impuestos")).andExpect(status().isUnauthorized());
    }

    // ---------------- Escritura: el permiso PRODUCTOS ----------------

    @Test
    void conElPermisoProductosOComoAdminSeCreaUnProductoConVariantes() throws Exception {
        when(servicio.crearProducto(any(), any())).thenReturn(new ProductoCreadoResponse(50L, List.of(100L)));

        mvc.perform(post(BASE + "/productos").with(Tokens.trabajador("PRODUCTOS")).contentType(MediaType.APPLICATION_JSON).content(PRODUCTO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idProducto").value(50))
                .andExpect(jsonPath("$.idsVariantes[0]").value(100));
        mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(PRODUCTO))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(ProductoRequest.class);
        verify(servicio).crearProducto(org.mockito.ArgumentMatchers.eq(TRABAJADOR), captor.capture());
        verify(servicio).crearProducto(org.mockito.ArgumentMatchers.eq(ADMIN), any());
        assertThat(captor.getValue().variantes()).hasSize(1);
        assertThat(captor.getValue().variantes().get(0).precioBase()).isEqualByComparingTo("2500000.00");
    }

    @Test
    void sinElPermisoProductosTodasLasEscriturasDan403() throws Exception {
        for (RequestPostProcessor token : new RequestPostProcessor[] {
                Tokens.trabajador(), Tokens.trabajador("CATEGORIAS"), Tokens.cliente()}) {
            mvc.perform(post(BASE + "/productos").with(token).contentType(MediaType.APPLICATION_JSON).content(PRODUCTO))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
            mvc.perform(put(BASE + "/productos/50").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"X\"}")).andExpect(status().isForbidden());
            mvc.perform(put(BASE + "/productos/50/estado").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"activo\":false}")).andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/productos/50/variantes").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content(VARIANTE)).andExpect(status().isForbidden());
            mvc.perform(put(BASE + "/variantes/7").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nombre\":\"X\",\"precioBase\":10}")).andExpect(status().isForbidden());
            mvc.perform(put(BASE + "/variantes/7/estado").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"activo\":true}")).andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE + "/productos").contentType(MediaType.APPLICATION_JSON).content(PRODUCTO)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void conElPermisoSeEditaSeCambiaElEstadoYSeAgreganVariantes() throws Exception {
        when(servicio.agregarVariante(any(), org.mockito.ArgumentMatchers.eq(50L), any())).thenReturn(new VarianteCreadaResponse(8L));
        RequestPostProcessor token = Tokens.trabajador("PRODUCTOS");

        mvc.perform(put(BASE + "/productos/50").with(token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"Portatil Y\"}")).andExpect(status().isNoContent());
        mvc.perform(put(BASE + "/productos/50/estado").with(token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":false}")).andExpect(status().isNoContent());
        mvc.perform(post(BASE + "/productos/50/variantes").with(token).contentType(MediaType.APPLICATION_JSON).content(VARIANTE))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.idVariante").value(8));
        mvc.perform(put(BASE + "/variantes/7").with(token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Mouse v2\",\"precioBase\":\"50000.50\"}")).andExpect(status().isNoContent());
        mvc.perform(put(BASE + "/variantes/7/estado").with(token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isNoContent());

        verify(servicio).actualizarProducto(TRABAJADOR, 50L, new ProductoEdicionRequest(1L, 2L, "Portatil Y", null, null));
        verify(servicio).cambiarEstadoProducto(TRABAJADOR, 50L, false);
        verify(servicio).actualizarVariante(TRABAJADOR, 7L, new VarianteEdicionRequest("Mouse v2", new BigDecimal("50000.50")));
        verify(servicio).cambiarEstadoVariante(TRABAJADOR, 7L, true);
    }

    // ---------------- Validacion de la entrada ----------------

    @Test
    void datosInvalidosDan400SinLlegarAlServicio() throws Exception {
        String sku51 = "S".repeat(51);
        for (String malo : new String[] {
                producto("[]"),                                       // sin variantes
                "{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"X\"}",   // falta la lista
                producto(varianteConPrecio("-1")),                    // precio negativo
                producto(varianteConPrecio("10.123")),                // 3 decimales
                producto(varianteConPrecio("\"abc\"")),               // texto que no es numero
                producto(varianteConPrecio("null")),                  // sin precio
                producto(varianteConPrecio("1234567890123.00")),      // mas de 12 enteros
                producto("[{\"sku\":\"\",\"nombre\":\"16GB\",\"precioBase\":10,\"controlaStock\":true}]"),
                producto("[{\"sku\":\"" + sku51 + "\",\"nombre\":\"16GB\",\"precioBase\":10,\"controlaStock\":true}]"),
                producto("[{\"sku\":\"A1\",\"nombre\":\"\",\"precioBase\":10,\"controlaStock\":true}]"),
                producto("[{\"sku\":\"A1\",\"nombre\":\"16GB\",\"precioBase\":10}]"),   // falta controlaStock
                "{\"idCategoria\":null,\"idImpuesto\":2,\"nombre\":\"X\",\"variantes\":" + varianteConPrecio("10") + "}",
                "{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"\",\"variantes\":" + varianteConPrecio("10") + "}",
                "{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"X\",\"descripcion\":\"" + "d".repeat(2001) + "\",\"variantes\":"
                        + varianteConPrecio("10") + "}",
                "{\"idCategoria\":1.5,\"idImpuesto\":2,\"nombre\":\"X\",\"variantes\":" + varianteConPrecio("10") + "}"}) {
            mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(malo))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(BASE + "/variantes/7").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"X\",\"precioBase\":-5}")).andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/variantes/abc/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void elPrecioEnTextoOEnNotacionCientificaSeAceptaComoNumero() throws Exception {
        when(servicio.crearProducto(any(), any())).thenReturn(new ProductoCreadoResponse(50L, List.of(100L)));

        mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content(producto(varianteConPrecio("\"2500000.00\"")))).andExpect(status().isCreated());
        mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content(producto(varianteConPrecio("1e3")))).andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(ProductoRequest.class);
        verify(servicio, org.mockito.Mockito.times(2)).crearProducto(any(), captor.capture());
        assertThat(captor.getAllValues().get(0).variantes().get(0).precioBase()).isEqualByComparingTo("2500000.00");
        assertThat(captor.getAllValues().get(1).variantes().get(0).precioBase()).isEqualByComparingTo("1000");
    }

    // ---------------- Errores de la base ----------------

    @Test
    void skuRepetidoImpuestoYCategoriaInvalidosSeTraducenAlEstadoDelSpec() throws Exception {
        when(servicio.crearProducto(any(), any()))
                .thenThrow(new NegocioException(CodigoError.SKU_REPETIDO, "SKU repetido", 20335))
                .thenThrow(new NegocioException(CodigoError.IMPUESTO_INVALIDO, "impuesto", 20334))
                .thenThrow(new NegocioException(CodigoError.CATEGORIA_NO_ENCONTRADA, "categoria", 20330))
                .thenThrow(new NegocioException(CodigoError.PRECIO_INVALIDO, "precio", 20337));

        String[][] esperados = {{"409", "SKU_REPETIDO"}, {"400", "IMPUESTO_INVALIDO"}, {"404", "CATEGORIA_NO_ENCONTRADA"},
                {"400", "PRECIO_INVALIDO"}};
        for (String[] e : esperados) {
            mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(PRODUCTO))
                    .andExpect(status().is(Integer.parseInt(e[0]))).andExpect(jsonPath("$.codigo").value(e[1]));
        }
    }

    @Test
    void productoYVarianteInexistentesDan404YElPermisoDeLaBaseDa403() throws Exception {
        doThrow(new NegocioException(CodigoError.PRODUCTO_NO_ENCONTRADO, "no existe", 20333))
                .when(servicio).cambiarEstadoProducto(ADMIN, 99L, true);
        doThrow(new NegocioException(CodigoError.VARIANTE_NO_ENCONTRADA, "no existe", 20336))
                .when(servicio).cambiarEstadoVariante(ADMIN, 99L, true);
        doThrow(new NegocioException(CodigoError.PERMISO_INSUFICIENTE, "sin permiso", 20320))
                .when(servicio).cambiarEstadoProducto(ADMIN, 5L, true);

        mvc.perform(put(BASE + "/productos/99/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("PRODUCTO_NO_ENCONTRADO"));
        mvc.perform(put(BASE + "/variantes/99/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("VARIANTE_NO_ENCONTRADA"));
        mvc.perform(put(BASE + "/productos/5/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("PERMISO_INSUFICIENTE"));
    }

    // ---------------- Imagen del producto ----------------

    @Test
    void laListaDevuelveLaUrlDeLaImagenYLaCreacionLaPasaAlServicio() throws Exception {
        when(servicio.crearProducto(any(), any())).thenReturn(new ProductoCreadoResponse(50L, List.of(100L)));
        String url = "https://abc.supabase.co/storage/v1/object/public/productos/productos/lap.png";
        String cuerpo = "{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"Portatil X\",\"imagenUrl\":\"" + url + "\",\"variantes\":"
                + varianteConPrecio("10") + "}";

        mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated());
        mvc.perform(put(BASE + "/productos/50").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"X\",\"imagenUrl\":\"" + url + "\"}"))
                .andExpect(status().isNoContent());

        var captor = ArgumentCaptor.forClass(ProductoRequest.class);
        verify(servicio).crearProducto(org.mockito.ArgumentMatchers.eq(ADMIN), captor.capture());
        assertThat(captor.getValue().imagenUrl()).isEqualTo(url);
        verify(servicio).actualizarProducto(ADMIN, 50L, new ProductoEdicionRequest(1L, 2L, "X", null, url));
    }

    @Test
    void unaUrlDeMasDe500CaracteresDa400SinLlegarAlServicio() throws Exception {
        String larga = "https://abc.supabase.co/" + "a".repeat(500);

        mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"X\",\"imagenUrl\":\"" + larga + "\",\"variantes\":"
                                + varianteConPrecio("10") + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/productos/50").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"X\",\"imagenUrl\":\"" + larga + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void unaUrlRechazadaPorElServicioDa400() throws Exception {
        when(servicio.crearProducto(any(), any()))
                .thenThrow(new NegocioException(CodigoError.IMAGEN_URL_INVALIDA, "La imagen debe estar en tu almacenamiento"));

        mvc.perform(post(BASE + "/productos").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idCategoria\":1,\"idImpuesto\":2,\"nombre\":\"X\",\"imagenUrl\":\"https://evil.example.com/a.png\","
                                + "\"variantes\":" + varianteConPrecio("10") + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("IMAGEN_URL_INVALIDA"));
    }
}
