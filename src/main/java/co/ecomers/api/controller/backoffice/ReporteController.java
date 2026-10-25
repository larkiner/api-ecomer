package co.ecomers.api.controller.backoffice;

import co.ecomers.api.dto.backoffice.CarritoAbandonadoResponse;
import co.ecomers.api.dto.backoffice.ConciliacionPagoResponse;
import co.ecomers.api.dto.backoffice.ConciliacionStockResponse;
import co.ecomers.api.dto.backoffice.EventoColaResponse;
import co.ecomers.api.dto.backoffice.PedidoPendienteResponse;
import co.ecomers.api.dto.backoffice.StockBajoResponse;
import co.ecomers.api.dto.backoffice.TopClienteResponse;
import co.ecomers.api.dto.backoffice.VentaDiariaResponse;
import co.ecomers.api.dto.backoffice.VentaProductoResponse;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.ReporteService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reportes del backoffice (rol EMPLEADO) sobre las vistas de ECOMERS. {@code limite} va de 1 a 500
 * (100 por defecto); las ventas aceptan {@code desde}/{@code hasta} (yyyy-MM-dd, ultimos 30 dias por defecto).
 */
@RestController
@RequestMapping("/api/v1/backoffice/reportes")
public class ReporteController {

    private final ReporteService servicio;

    public ReporteController(ReporteService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/stock-bajo")
    public List<StockBajoResponse> stockBajo(EmpleadoActual e, @RequestParam(required = false) Integer limite) {
        return servicio.stockBajo(e, limite);
    }

    @GetMapping("/conciliacion-stock")
    public List<ConciliacionStockResponse> conciliacionStock(EmpleadoActual e,
                                                             @RequestParam(required = false) Integer limite) {
        return servicio.conciliacionStock(e, limite);
    }

    @GetMapping("/pedidos-pendientes")
    public List<PedidoPendienteResponse> pedidosPendientes(EmpleadoActual e,
                                                           @RequestParam(required = false) Integer limite) {
        return servicio.pedidosPendientes(e, limite);
    }

    @GetMapping("/ventas-diarias")
    public List<VentaDiariaResponse> ventasDiarias(
            EmpleadoActual e,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return servicio.ventasDiarias(e, desde, hasta);
    }

    @GetMapping("/ventas-producto")
    public List<VentaProductoResponse> ventasProducto(
            EmpleadoActual e,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Integer limite) {
        return servicio.ventasProducto(e, desde, hasta, limite);
    }

    @GetMapping("/top-clientes")
    public List<TopClienteResponse> topClientes(EmpleadoActual e, @RequestParam(required = false) Integer limite) {
        return servicio.topClientes(e, limite);
    }

    @GetMapping("/conciliacion-pagos")
    public List<ConciliacionPagoResponse> conciliacionPagos(EmpleadoActual e,
                                                            @RequestParam(required = false) Integer limite) {
        return servicio.conciliacionPagos(e, limite);
    }

    @GetMapping("/carritos-abandonados")
    public List<CarritoAbandonadoResponse> carritosAbandonados(EmpleadoActual e,
                                                               @RequestParam(required = false) Integer limite) {
        return servicio.carritosAbandonados(e, limite);
    }

    @GetMapping("/cola-pendiente")
    public List<EventoColaResponse> colaPendiente(EmpleadoActual e, @RequestParam(required = false) Integer limite) {
        return servicio.colaPendiente(e, limite);
    }
}
