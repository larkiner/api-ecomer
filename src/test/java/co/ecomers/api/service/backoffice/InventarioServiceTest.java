package co.ecomers.api.service.backoffice;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import co.ecomers.api.dto.backoffice.AjusteInventarioRequest;
import co.ecomers.api.dto.backoffice.EntradaInventarioRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.InventarioRepository;
import org.junit.jupiter.api.Test;

class InventarioServiceTest {

    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "ana.bodega");

    private final InventarioRepository repo = mock(InventarioRepository.class);
    private final InventarioService servicio = new InventarioService(repo);

    @Test
    void entradaConObservacionRecortada() {
        servicio.registrarEntrada(EMPLEADO, 5L, new EntradaInventarioRequest(10, "  Factura 123 "));
        verify(repo).entrada(EMPLEADO, 5L, 10, "Factura 123");
    }

    @Test
    void entradaSinObservacionPasaNulo() {
        servicio.registrarEntrada(EMPLEADO, 5L, new EntradaInventarioRequest(10, "   "));
        verify(repo).entrada(EMPLEADO, 5L, 10, null);
    }

    @Test
    void ajusteNegativoConObservacion() {
        servicio.registrarAjuste(EMPLEADO, 5L, new AjusteInventarioRequest(-2, "Merma: caja golpeada"));
        verify(repo).ajuste(EMPLEADO, 5L, -2, "Merma: caja golpeada");
    }

    @Test
    void ajusteEnCeroEsValidacion() {
        assertThatThrownBy(() -> servicio.registrarAjuste(EMPLEADO, 5L, new AjusteInventarioRequest(0, "x")))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        verifyNoInteractions(repo);
    }

    @Test
    void ajusteSinObservacionEsObservacionRequerida() {
        assertThatThrownBy(() -> servicio.registrarAjuste(EMPLEADO, 5L, new AjusteInventarioRequest(3, "  ")))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.OBSERVACION_REQUERIDA);
        verifyNoInteractions(repo);
    }

    @Test
    void varianteNoPositivaEsValidacion() {
        assertThatThrownBy(() -> servicio.registrarEntrada(EMPLEADO, 0L, new EntradaInventarioRequest(1, null)))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        verifyNoInteractions(repo);
    }
}
