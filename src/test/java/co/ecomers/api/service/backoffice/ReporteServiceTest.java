package co.ecomers.api.service.backoffice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.ReporteRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReporteServiceTest {

    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "ana");
    // 2026-10-19 03:00 UTC = 2026-10-18 22:00 en Colombia: "hoy" debe ser el 18.
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC);

    private final ReporteRepository repo = mock(ReporteRepository.class);
    private final ReporteService servicio = new ReporteService(repo, RELOJ);

    @Test
    void limitePorDefectoEs100() {
        servicio.stockBajo(EMPLEADO, null);
        verify(repo).stockBajo(EMPLEADO, 100);
    }

    @Test
    void limiteFueraDeRangoEsValidacion() {
        for (int malo : new int[] {0, -1, 501}) {
            assertThatThrownBy(() -> servicio.topClientes(EMPLEADO, malo))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        }
        verifyNoInteractions(repo);
    }

    @Test
    void ventasSinFechasSonLosUltimos30DiasEnHoraDeColombia() {
        servicio.ventasDiarias(EMPLEADO, null, null);
        verify(repo).ventasDiarias(EMPLEADO, LocalDate.of(2026, 9, 19), LocalDate.of(2026, 10, 18));
    }

    @Test
    void ventasSoloConDesdeLlegaHastaHoy() {
        servicio.ventasProducto(EMPLEADO, LocalDate.of(2026, 10, 1), null, null);
        verify(repo).ventasProducto(EMPLEADO, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 18), 100);
    }

    @Test
    void rangoInvertidoEsValidacion() {
        assertThatThrownBy(() -> servicio.ventasDiarias(EMPLEADO, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1)))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
    }

    @Test
    void rangoDeMasDeUnAnioEsValidacion() {
        assertThatThrownBy(() -> servicio.ventasDiarias(EMPLEADO, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 3)))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        verifyNoInteractions(repo);
    }

    @Test
    void todosLosReportesDeleganConElLimite() {
        servicio.conciliacionStock(EMPLEADO, 10);
        servicio.pedidosPendientes(EMPLEADO, 10);
        servicio.conciliacionPagos(EMPLEADO, 10);
        servicio.carritosAbandonados(EMPLEADO, 10);
        servicio.colaPendiente(EMPLEADO, 10);
        verify(repo).conciliacionStock(EMPLEADO, 10);
        verify(repo).pedidosPendientes(EMPLEADO, 10);
        verify(repo).conciliacionPagos(EMPLEADO, 10);
        verify(repo).carritosAbandonados(EMPLEADO, 10);
        verify(repo).colaPendiente(EMPLEADO, 10);
    }
}
