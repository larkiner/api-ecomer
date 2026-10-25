package co.ecomers.api.service.backoffice;

import co.ecomers.api.dto.backoffice.CarritoAbandonadoResponse;
import co.ecomers.api.dto.backoffice.ConciliacionPagoResponse;
import co.ecomers.api.dto.backoffice.ConciliacionStockResponse;
import co.ecomers.api.dto.backoffice.EventoColaResponse;
import co.ecomers.api.dto.backoffice.PedidoPendienteResponse;
import co.ecomers.api.dto.backoffice.StockBajoResponse;
import co.ecomers.api.dto.backoffice.TopClienteResponse;
import co.ecomers.api.dto.backoffice.VentaDiariaResponse;
import co.ecomers.api.dto.backoffice.VentaProductoResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.ReporteRepository;
import co.ecomers.api.util.FechaBd;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reportes del backoffice. Acota cuantas filas y cuantos dias se piden: las vistas no tienen
 * paginacion propia. Los dias son los de Colombia, igual que en la base.
 */
@Service
public class ReporteService {

    static final int LIMITE_POR_DEFECTO = 100;
    static final int LIMITE_MAXIMO = 500;
    static final int DIAS_POR_DEFECTO = 30;
    static final int DIAS_MAXIMOS = 366;

    private final ReporteRepository repo;
    private final Clock reloj;

    @Autowired
    public ReporteService(ReporteRepository repo) {
        this(repo, Clock.system(FechaBd.COLOMBIA));
    }

    ReporteService(ReporteRepository repo, Clock reloj) {
        this.repo = repo;
        this.reloj = reloj;
    }

    public List<StockBajoResponse> stockBajo(EmpleadoActual e, Integer limite) {
        return repo.stockBajo(e, limite(limite));
    }

    public List<ConciliacionStockResponse> conciliacionStock(EmpleadoActual e, Integer limite) {
        return repo.conciliacionStock(e, limite(limite));
    }

    public List<PedidoPendienteResponse> pedidosPendientes(EmpleadoActual e, Integer limite) {
        return repo.pedidosPendientes(e, limite(limite));
    }

    public List<VentaDiariaResponse> ventasDiarias(EmpleadoActual e, LocalDate desde, LocalDate hasta) {
        Rango r = rango(desde, hasta);
        return repo.ventasDiarias(e, r.desde(), r.hasta());
    }

    public List<VentaProductoResponse> ventasProducto(EmpleadoActual e, LocalDate desde, LocalDate hasta, Integer limite) {
        int tope = limite(limite);
        Rango r = rango(desde, hasta);
        return repo.ventasProducto(e, r.desde(), r.hasta(), tope);
    }

    public List<TopClienteResponse> topClientes(EmpleadoActual e, Integer limite) {
        return repo.topClientes(e, limite(limite));
    }

    public List<ConciliacionPagoResponse> conciliacionPagos(EmpleadoActual e, Integer limite) {
        return repo.conciliacionPagos(e, limite(limite));
    }

    public List<CarritoAbandonadoResponse> carritosAbandonados(EmpleadoActual e, Integer limite) {
        return repo.carritosAbandonados(e, limite(limite));
    }

    public List<EventoColaResponse> colaPendiente(EmpleadoActual e, Integer limite) {
        return repo.colaPendiente(e, limite(limite));
    }

    private static int limite(Integer limite) {
        if (limite == null) {
            return LIMITE_POR_DEFECTO;
        }
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new NegocioException(CodigoError.VALIDACION, "limite debe estar entre 1 y " + LIMITE_MAXIMO);
        }
        return limite;
    }

    private Rango rango(LocalDate desde, LocalDate hasta) {
        LocalDate fin = hasta != null ? hasta : LocalDate.now(reloj.withZone(FechaBd.COLOMBIA));
        LocalDate inicio = desde != null ? desde : fin.minusDays(DIAS_POR_DEFECTO - 1L);
        if (inicio.isAfter(fin)) {
            throw new NegocioException(CodigoError.VALIDACION, "desde no puede ser posterior a hasta");
        }
        if (ChronoUnit.DAYS.between(inicio, fin) + 1 > DIAS_MAXIMOS) {
            throw new NegocioException(CodigoError.VALIDACION, "El rango admite hasta " + DIAS_MAXIMOS + " días");
        }
        return new Rango(inicio, fin);
    }

    private record Rango(LocalDate desde, LocalDate hasta) { }
}
