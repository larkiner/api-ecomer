package co.ecomers.api.controller.tienda;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.Tokens;
import co.ecomers.api.dto.comun.PaginaResponse;
import co.ecomers.api.dto.tienda.AgregarItemRequest;
import co.ecomers.api.dto.tienda.CambiarCantidadRequest;
import co.ecomers.api.dto.tienda.CancelarPedidoRequest;
import co.ecomers.api.dto.tienda.CrearPedidoRequest;
import co.ecomers.api.dto.tienda.IniciarPagoResponse;
import co.ecomers.api.dto.tienda.PedidoCreadoResponse;
import co.ecomers.api.dto.tienda.CategoriaPublicaResponse;
import co.ecomers.api.dto.tienda.VarianteResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.service.tienda.CarritoService;
import co.ecomers.api.service.tienda.CatalogoService;
import co.ecomers.api.service.tienda.PagoService;
import co.ecomers.api.service.tienda.PedidoService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@PruebaWeb({CatalogoController.class, CarritoController.class, PedidoController.class})
class TiendaControllersTest {

    private static final ClienteActual CLIENTE = new ClienteActual(7L, "ana@correo.com");

    @Autowired MockMvc mvc;
    @MockitoBean CatalogoService catalogo;
    @MockitoBean CarritoService carrito;
    @MockitoBean PedidoService pedidos;
    @MockitoBean PagoService pagos;

    private static VarianteResponse variante() {
        return new VarianteResponse(10L, "LAP-001", "Portátil X - 16GB", true, new BigDecimal("2500000.00"),
                new BigDecimal("250000.00"), new BigDecimal("2250000.00"), new BigDecimal("0.1900"), 3L,
                "https://abc.supabase.co/storage/v1/object/public/productos/productos/lap.png");
    }

    // ---------------- Catalogo (publico) ----------------

    @Test
    void catalogoEsPublicoConCacheControlYPreciosComoTexto() throws Exception {
        when(catalogo.buscar(null, null, 0, 20)).thenReturn(new PaginaResponse<>(List.of(variante()), 0, 20, 1));

        mvc.perform(get("/api/v1/catalogo/variantes"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.contenido[0].sku").value("LAP-001"))
                .andExpect(jsonPath("$.contenido[0].precioFinal").value("2250000.00"))
                .andExpect(jsonPath("$.contenido[0].imagenUrl").value("https://abc.supabase.co/storage/v1/object/public/productos/productos/lap.png"));
    }

    @Test
    void catalogoPasaBusquedaYPaginacionAlServicio() throws Exception {
        when(catalogo.buscar("lap", null, 2, 10)).thenReturn(new PaginaResponse<>(List.of(), 2, 10, 0));
        mvc.perform(get("/api/v1/catalogo/variantes?q=lap&page=2&size=10")).andExpect(status().isOk());
        verify(catalogo).buscar("lap", null, 2, 10);
    }

    @Test
    void catalogoPasaLaCategoriaAlServicioYUnaNoNumericaEsValidacion() throws Exception {
        when(catalogo.buscar(null, 30L, 0, 20)).thenReturn(new PaginaResponse<>(List.of(variante()), 0, 20, 1));

        mvc.perform(get("/api/v1/catalogo/variantes?categoria=30"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        verify(catalogo).buscar(null, 30L, 0, 20);

        mvc.perform(get("/api/v1/catalogo/variantes?categoria=abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    void lasCategoriasSonPublicasConCacheControlYSinCamposNulos() throws Exception {
        when(catalogo.listarCategorias()).thenReturn(List.of(
                new CategoriaPublicaResponse(30L, null, "celulares", "https://x/c.png"),
                new CategoriaPublicaResponse(32L, 30L, "Samsung", null)));

        mvc.perform(get("/api/v1/catalogo/categorias"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$[0].nombre").value("celulares"))
                .andExpect(jsonPath("$[0].idPadre").doesNotExist())
                .andExpect(jsonPath("$[0].imagenUrl").value("https://x/c.png"))
                .andExpect(jsonPath("$[1].idPadre").value(30))
                .andExpect(jsonPath("$[1].imagenUrl").doesNotExist());
    }

    @Test
    void parametroNoNumericoEsValidacion() throws Exception {
        mvc.perform(get("/api/v1/catalogo/variantes?page=abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
    }

    @Test
    void varianteInexistenteDa404() throws Exception {
        when(catalogo.obtener(99L)).thenThrow(new NegocioException(CodigoError.NO_ENCONTRADO, "Variante no encontrada"));
        mvc.perform(get("/api/v1/catalogo/variantes/99"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("NO_ENCONTRADO"));
    }

    @Test
    void lasRespuestasLlevanCabecerasDeSeguridad() throws Exception {
        when(catalogo.obtener(10L)).thenReturn(variante());
        mvc.perform(get("/api/v1/catalogo/variantes/10"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    // ---------------- Autorizacion ----------------

    @Test
    void sinTokenDa401ConJson() throws Exception {
        mvc.perform(delete("/api/v1/carrito"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(jsonPath("$.codigo").value("NO_AUTENTICADO"));
        verifyNoInteractions(carrito);
    }

    @Test
    void empleadoNoUsaElCarritoDeClientes() throws Exception {
        mvc.perform(delete("/api/v1/carrito").with(Tokens.empleado()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        verifyNoInteractions(carrito);
    }

    @Test
    void clienteSinIdClienteDa403() throws Exception {
        mvc.perform(delete("/api/v1/carrito").with(Tokens.clienteSinId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CLIENTE_NO_IDENTIFICADO"));
        verifyNoInteractions(carrito);
    }

    @Test
    void rutaNoDeclaradaQuedaDenegada() throws Exception {
        mvc.perform(get("/api/v1/clientes").with(Tokens.cliente())).andExpect(status().isForbidden());
    }

    // ---------------- Carrito ----------------

    @Test
    void agregarUsaElClienteDelTokenNoElDelCuerpo() throws Exception {
        mvc.perform(post("/api/v1/carrito/items").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idVariante\":5,\"cantidad\":2,\"idCliente\":999}"))
                .andExpect(status().isNoContent());
        verify(carrito).agregar(CLIENTE, new AgregarItemRequest(5L, 2));
    }

    @Test
    void cuerposInvalidosSonValidacion() throws Exception {
        for (String cuerpo : new String[] {"{\"idVariante\":5,\"cantidad\":0}", "{\"idVariante\":5,\"cantidad\":1000}",
                "{\"cantidad\":1}", "{\"idVariante\":-5,\"cantidad\":1}", "no es json"}) {
            mvc.perform(post("/api/v1/carrito/items").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                            .content(cuerpo))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        verifyNoInteractions(carrito);
    }

    @Test
    void errorDeLaBaseLlegaConSuCodigo() throws Exception {
        doThrow(new NegocioException(CodigoError.VARIANTE_NO_DISPONIBLE, "La variante 5 no existe o no está activa"))
                .when(carrito).agregar(CLIENTE, new AgregarItemRequest(5L, 2));

        mvc.perform(post("/api/v1/carrito/items").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idVariante\":5,\"cantidad\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("VARIANTE_NO_DISPONIBLE"))
                .andExpect(jsonPath("$.mensaje").value("La variante 5 no existe o no está activa"));
    }

    @Test
    void cambiarQuitarYVaciar() throws Exception {
        mvc.perform(put("/api/v1/carrito/items/5").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cantidad\":4}")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/carrito/items/5").with(Tokens.cliente())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/carrito").with(Tokens.cliente())).andExpect(status().isNoContent());
        verify(carrito).cambiarCantidad(CLIENTE, 5L, new CambiarCantidadRequest(4));
        verify(carrito).quitar(CLIENTE, 5L);
        verify(carrito).vaciar(CLIENTE);
    }

    // ---------------- Pedidos y pagos ----------------

    @Test
    void crearPedidoSinCuerpoDevuelve201ConLocation() throws Exception {
        when(pedidos.crear(CLIENTE, null)).thenReturn(new PedidoCreadoResponse(1001L));

        mvc.perform(post("/api/v1/pedidos").with(Tokens.cliente()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/pedidos/1001"))
                .andExpect(jsonPath("$.idPedido").value(1001));
    }

    @Test
    void crearPedidoConDireccion() throws Exception {
        when(pedidos.crear(CLIENTE, new CrearPedidoRequest(5L))).thenReturn(new PedidoCreadoResponse(1002L));
        mvc.perform(post("/api/v1/pedidos").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idDireccion\":5}")).andExpect(status().isCreated()).andExpect(jsonPath("$.idPedido").value(1002));
    }

    @Test
    void stockInsuficienteDa409ConElMensajeDeLaBase() throws Exception {
        when(pedidos.crear(CLIENTE, null)).thenThrow(
                new NegocioException(CodigoError.STOCK_INSUFICIENTE, "Stock insuficiente para LAP-001"));
        mvc.perform(post("/api/v1/pedidos").with(Tokens.cliente()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.mensaje").value("Stock insuficiente para LAP-001"));
    }

    @Test
    void cancelarConYSinMotivo() throws Exception {
        mvc.perform(post("/api/v1/pedidos/10/cancelacion").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"ya no\"}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/pedidos/11/cancelacion").with(Tokens.cliente())).andExpect(status().isNoContent());
        verify(pedidos).cancelar(CLIENTE, 10L, new CancelarPedidoRequest("ya no"));
        verify(pedidos).cancelar(CLIENTE, 11L, null);
    }

    @Test
    void motivoDemasiadoLargoOIdNoNumericoSonValidacion() throws Exception {
        mvc.perform(post("/api/v1/pedidos/10/cancelacion").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"motivo\":\"" + "x".repeat(401) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/pedidos/abc/cancelacion").with(Tokens.cliente())).andExpect(status().isBadRequest());
        verifyNoInteractions(pedidos);
    }

    @Test
    void pedidoAjenoDa404() throws Exception {
        doThrow(new NegocioException(CodigoError.PEDIDO_NO_ENCONTRADO, "El pedido no existe"))
                .when(pedidos).cancelar(CLIENTE, 99L, null);
        mvc.perform(post("/api/v1/pedidos/99/cancelacion").with(Tokens.cliente()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("PEDIDO_NO_ENCONTRADO"));
    }

    @Test
    void iniciarPagoDevuelveElCheckout() throws Exception {
        when(pagos.iniciar(CLIENTE, 10L)).thenReturn(new IniciarPagoResponse("A1B2", 23_800_000L, "COP", "pub_test_x",
                "firma", "http://localhost:5173/pedidos/10/resultado", "https://checkout.wompi.co/p/?reference=A1B2"));

        mvc.perform(post("/api/v1/pedidos/10/pagos").with(Tokens.cliente()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referencia").value("A1B2"))
                .andExpect(jsonPath("$.montoEnCentavos").value(23800000))
                .andExpect(jsonPath("$.checkoutUrl").value(containsString("reference=A1B2")));
    }

    @Test
    void pagoSinWompiConfiguradoDa503() throws Exception {
        when(pagos.iniciar(any(), org.mockito.ArgumentMatchers.anyLong())).thenThrow(
                new NegocioException(CodigoError.PASARELA_NO_CONFIGURADA, "El pago en línea no está disponible"));
        mvc.perform(post("/api/v1/pedidos/10/pagos").with(Tokens.cliente()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.codigo").value("PASARELA_NO_CONFIGURADA"));
    }
}
