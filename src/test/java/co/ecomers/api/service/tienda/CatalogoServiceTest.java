package co.ecomers.api.service.tienda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.tienda.CategoriaPublicaResponse;
import co.ecomers.api.dto.tienda.VarianteResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.repository.tienda.CatalogoRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogoServiceTest {

    private final CatalogoRepository repo = mock(CatalogoRepository.class);
    private final CatalogoService servicio = new CatalogoService(repo);

    private static VarianteResponse variante(long id) {
        return new VarianteResponse(id, "SKU-" + id, "Producto " + id, true, new BigDecimal("1000.00"),
                BigDecimal.ZERO, new BigDecimal("1000.00"), new BigDecimal("0.1900"), null, null);
    }

    @Test
    void buscaConOffsetPorPaginaYDevuelveElTotal() {
        when(repo.contar("PORTATIL", null)).thenReturn(45L);
        when(repo.listar("PORTATIL", null, 20, 10)).thenReturn(List.of(variante(1)));

        var pagina = servicio.buscar("  PORTATIL ", null, 2, 10);

        assertThat(pagina.total()).isEqualTo(45);
        assertThat(pagina.pagina()).isEqualTo(2);
        assertThat(pagina.tamano()).isEqualTo(10);
        assertThat(pagina.contenido()).hasSize(1);
    }

    @Test
    void busquedaEnBlancoNoFiltra() {
        when(repo.contar(null, null)).thenReturn(1L);
        when(repo.listar(null, null, 0, 20)).thenReturn(List.of(variante(1)));

        assertThat(servicio.buscar("   ", null, 0, 20).contenido()).hasSize(1);
    }

    @Test
    void filtraPorCategoriaYLaPasaAlRepositorio() {
        when(repo.contar(null, 30L)).thenReturn(2L);
        when(repo.listar(null, 30L, 0, 20)).thenReturn(List.of(variante(1), variante(2)));

        var pagina = servicio.buscar(null, 30L, 0, 20);

        assertThat(pagina.total()).isEqualTo(2);
        assertThat(pagina.contenido()).hasSize(2);
    }

    @Test
    void unaCategoriaNoPositivaEsValidacionYNoConsultaLaBase() {
        for (long mala : new long[] {0L, -5L}) {
            assertThatThrownBy(() -> servicio.buscar(null, mala, 0, 20))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        }
        verifyNoInteractions(repo);
    }

    @Test
    void lasCategoriasSalenTalComoLasEntregaLaBaseSinInventarNinguna() {
        var activa = new CategoriaPublicaResponse(30L, null, "celulares", "https://x/c.png");
        when(repo.listarCategorias()).thenReturn(List.of(activa));

        assertThat(servicio.listarCategorias()).containsExactly(activa);
    }

    @Test
    void paginaFueraDelTotalNoConsultaFilas() {
        when(repo.contar(null, null)).thenReturn(5L);

        var pagina = servicio.buscar(null, null, 3, 10);

        assertThat(pagina.contenido()).isEmpty();
        assertThat(pagina.total()).isEqualTo(5);
        verify(repo, never()).listar(any(), any(), anyInt(), anyInt());
    }

    @Test
    void parametrosFueraDeRangoSonValidacion() {
        for (int[] malo : new int[][] {{-1, 20}, {0, 0}, {0, 51}, {100_001, 20}}) {
            assertThatThrownBy(() -> servicio.buscar(null, null, malo[0], malo[1]))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        }
    }

    @Test
    void busquedaDemasiadoLargaEsValidacion() {
        assertThatThrownBy(() -> servicio.buscar("x".repeat(101), null, 0, 20))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
    }

    @Test
    void obtenerVarianteExistente() {
        when(repo.buscar(7L)).thenReturn(Optional.of(variante(7)));
        assertThat(servicio.obtener(7L).idVariante()).isEqualTo(7L);
    }

    @Test
    void varianteInexistenteONoPositivaEsNoEncontrada() {
        when(repo.buscar(99L)).thenReturn(Optional.empty());
        for (long id : new long[] {99L, 0L, -3L}) {
            assertThatThrownBy(() -> servicio.obtener(id))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.NO_ENCONTRADO);
        }
        verify(repo, never()).buscar(0L);
    }
}
