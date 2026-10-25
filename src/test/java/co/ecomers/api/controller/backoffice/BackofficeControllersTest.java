package co.ecomers.api.controller.backoffice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.Tokens;
import co.ecomers.api.dto.backoffice.AjusteInventarioRequest;
import co.ecomers.api.dto.backoffice.EntradaInventarioRequest;
import co.ecomers.api.dto.backoffice.PedidoPendienteResponse;
import co.ecomers.api.dto.backoffice.StockBajoResponse;
import co.ecomers.api.dto.backoffice.VentaDiariaResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.InventarioService;
import co.ecomers.api.service.backoffice.ReporteService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@PruebaWeb({InventarioController.class, ReporteController.class})
class BackofficeControllersTest {

    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "ana.bodega@ecomers.co");

    @Autowired MockMvc mvc;
    @MockitoBean InventarioService inventario;
    @MockitoBean ReporteService reportes;

    // ---------------- Autorizacion ----------------

    @Test
    void sinTokenDa401() throws Exception {
        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reportes);
    }

    @Test
    void unClienteNoEntraAlBackoffice() throws Exception {
        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").with(Tokens.cliente()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        mvc.perform(post("/api/v1/backoffice/inventario/5/entradas").with(Tokens.cliente())
                .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":1}")).andExpect(status().isForbidden());
        verifyNoInteractions(reportes, inventario);
    }

    // ---------------- Inventario ----------------

    @Test
    void entradaConElEmpleadoDelToken() throws Exception {
        mvc.perform(post("/api/v1/backoffice/inventario/5/entradas").with(Tokens.empleado())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":10,\"observacion\":\"Factura 123\"}"))
                .andExpect(status().isNoContent());
        verify(inventario).registrarEntrada(EMPLEADO, 5L, new EntradaInventarioRequest(10, "Factura 123"));
    }

    @Test
    void unTokenDeEmpleadoSinIdEmpleadoDa403ConSuCodigoYNoLlegaAlServicio() throws Exception {
        mvc.perform(post("/api/v1/backoffice/inventario/5/entradas").with(Tokens.empleadoSinId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":10}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("EMPLEADO_NO_IDENTIFICADO"));
        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").with(Tokens.empleadoSinId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("EMPLEADO_NO_IDENTIFICADO"));
        verifyNoInteractions(reportes, inventario);
    }

    @Test
    void elAdminTambienUsaInventarioYReportesComoEmpleado() throws Exception {
        mvc.perform(post("/api/v1/backoffice/inventario/5/entradas").with(Tokens.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":10}"))
                .andExpect(status().isNoContent());
        verify(inventario).registrarEntrada(new EmpleadoActual(Tokens.ID_ADMIN, "admin@ecomers.co"), 5L,
                new EntradaInventarioRequest(10, null));
    }

    @Test
    void ajusteSinObservacionOCantidadFueraDeRangoSonValidacion() throws Exception {
        for (String cuerpo : new String[] {"{\"cantidad\":-2}", "{\"cantidad\":-2,\"observacion\":\"  \"}",
                "{\"cantidad\":100001,\"observacion\":\"x\"}"}) {
            mvc.perform(post("/api/v1/backoffice/inventario/5/ajustes").with(Tokens.empleado())
                            .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        verifyNoInteractions(inventario);
    }

    @Test
    void ajusteQueDejariaElStockBajoLoReservadoDa409() throws Exception {
        doThrow(new NegocioException(CodigoError.AJUSTE_INVALIDO, "El ajuste dejaria el total por debajo de lo reservado"))
                .when(inventario).registrarAjuste(eq(EMPLEADO), eq(5L), any(AjusteInventarioRequest.class));

        mvc.perform(post("/api/v1/backoffice/inventario/5/ajustes").with(Tokens.empleado())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\":-50,\"observacion\":\"Conteo\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("AJUSTE_INVALIDO"));
    }

    // ---------------- Reportes ----------------

    @Test
    void stockBajo() throws Exception {
        when(reportes.stockBajo(EMPLEADO, null)).thenReturn(List.of(new StockBajoResponse(5L, "LAP-001", "Portátil", 6, 3, 3)));
        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").with(Tokens.empleado()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("LAP-001"))
                .andExpect(jsonPath("$[0].disponible").value(3));
    }

    @Test
    void ventasDiariasConRangoYFechasEnIso() throws Exception {
        when(reportes.ventasDiarias(EMPLEADO, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18))).thenReturn(List.of(
                new VentaDiariaResponse(LocalDate.of(2026, 10, 18), 3, new BigDecimal("600000.00"),
                        new BigDecimal("114000.00"), new BigDecimal("714000.00"))));

        mvc.perform(get("/api/v1/backoffice/reportes/ventas-diarias?desde=2026-10-01&hasta=2026-10-18").with(Tokens.empleado()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fecha").value("2026-10-18"))
                .andExpect(jsonPath("$[0].total").value("714000.00"));
    }

    @Test
    void fechaMalFormadaEsValidacion() throws Exception {
        mvc.perform(get("/api/v1/backoffice/reportes/ventas-diarias?desde=18-10-2026").with(Tokens.empleado()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
        verifyNoInteractions(reportes);
    }

    @Test
    void fechaHoraSaleConElDesfaseDeColombia() throws Exception {
        when(reportes.pedidosPendientes(EMPLEADO, 10)).thenReturn(List.of(new PedidoPendienteResponse(1L, "A1B2", 7L,
                "Ana", "ana@correo.co", new BigDecimal("238000.00"), OffsetDateTime.parse("2026-10-18T10:00:00-05:00"),
                OffsetDateTime.parse("2026-10-18T10:30:00-05:00"), 12L)));

        mvc.perform(get("/api/v1/backoffice/reportes/pedidos-pendientes?limite=10").with(Tokens.empleado()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fechaExpiracion").value("2026-10-18T10:30:00-05:00"))
                .andExpect(jsonPath("$[0].minutosRestantes").value(12));
    }

    @Test
    void limiteInvalidoDelServicioEsValidacion() throws Exception {
        when(reportes.topClientes(EMPLEADO, 999)).thenThrow(new NegocioException(CodigoError.VALIDACION, "limite"));
        mvc.perform(get("/api/v1/backoffice/reportes/top-clientes?limite=999").with(Tokens.empleado()))
                .andExpect(status().isBadRequest());
    }
}
