package co.ecomers.api.service.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.backoffice.CategoriaRequest;
import co.ecomers.api.dto.backoffice.ProductoCreadoResponse;
import co.ecomers.api.dto.backoffice.ProductoEdicionRequest;
import co.ecomers.api.dto.backoffice.ProductoRequest;
import co.ecomers.api.dto.backoffice.VarianteEdicionRequest;
import co.ecomers.api.dto.backoffice.VarianteRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.CatalogoAdminRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogoAdminServiceTest {

    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "ana.bodega@ecomers.co");

    private final CatalogoAdminRepository repo = mock(CatalogoAdminRepository.class);
    private final ImagenService imagenes = mock(ImagenService.class);
    private final CatalogoAdminService servicio = new CatalogoAdminService(repo, imagenes);

    @Test
    void crearCategoriaRecortaElNombreYDevuelveElId() {
        when(repo.crearCategoria(EMPLEADO, "Portatiles", 10L, null)).thenReturn(20L);

        var creada = servicio.crearCategoria(EMPLEADO, new CategoriaRequest("  Portatiles  ", 10L, null));

        assertThat(creada.idCategoria()).isEqualTo(20L);
    }

    @Test
    void actualizarCategoriaRecortaElNombreYPasaElPadre() {
        servicio.actualizarCategoria(EMPLEADO, 20L, new CategoriaRequest(" Gamer ", null, null));

        verify(repo).actualizarCategoria(EMPLEADO, 20L, "Gamer", null, null);
    }

    @Test
    void crearYEditarUnaCategoriaGuardanLaUrlYaValidadaYSinUrlLaQuitan() {
        when(imagenes.validarUrl("  https://x/c.png ")).thenReturn("https://x/c.png");
        when(repo.crearCategoria(EMPLEADO, "Audio", null, "https://x/c.png")).thenReturn(21L);

        assertThat(servicio.crearCategoria(EMPLEADO, new CategoriaRequest("Audio", null, "  https://x/c.png ")).idCategoria())
                .isEqualTo(21L);
        servicio.actualizarCategoria(EMPLEADO, 21L, new CategoriaRequest("Audio", null, null));

        verify(repo).actualizarCategoria(EMPLEADO, 21L, "Audio", null, null);
    }

    @Test
    void unaUrlInvalidaEnUnaCategoriaFrenaLaOperacionAntesDeTocarLaBase() {
        when(imagenes.validarUrl("https://evil.example.com/a.png"))
                .thenThrow(new NegocioException(CodigoError.IMAGEN_URL_INVALIDA, "La imagen debe estar en tu almacenamiento"));

        assertThatThrownBy(() -> servicio.crearCategoria(EMPLEADO, new CategoriaRequest("X", null, "https://evil.example.com/a.png")))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> servicio.actualizarCategoria(EMPLEADO, 20L, new CategoriaRequest("X", null, "https://evil.example.com/a.png")))
                .isInstanceOf(NegocioException.class);

        verifyNoInteractions(repo);
    }

    @Test
    void cambiarEstadoYListasDelegan() {
        servicio.cambiarEstadoCategoria(EMPLEADO, 20L, false);
        servicio.listarCategorias(EMPLEADO);
        servicio.listarImpuestos(EMPLEADO);

        verify(repo).cambiarEstadoCategoria(EMPLEADO, 20L, false);
        verify(repo).listarCategorias(EMPLEADO);
        verify(repo).listarImpuestos(EMPLEADO);
    }

    // ---------------- Productos y variantes ----------------

    private static final VarianteRequest VARIANTE = new VarianteRequest("LAP-001", "16GB", new BigDecimal("2500000.00"), true);

    @Test
    void crearProductoDelegaEnLaUnicaTransaccionDelRepositorio() {
        var req = new ProductoRequest(1L, 2L, "Portatil X", null, null, List.of(VARIANTE));
        var creado = new ProductoCreadoResponse(50L, List.of(100L));
        when(repo.crearProductoConVariantes(EMPLEADO, req)).thenReturn(creado);

        assertThat(servicio.crearProducto(EMPLEADO, req)).isEqualTo(creado);
    }

    @Test
    void agregarVarianteDevuelveElIdDeLaNueva() {
        when(repo.agregarVariante(EMPLEADO, 50L, VARIANTE)).thenReturn(7L);

        assertThat(servicio.agregarVariante(EMPLEADO, 50L, VARIANTE).idVariante()).isEqualTo(7L);
    }

    @Test
    void edicionesYEstadosDelegan() {
        var edicionProducto = new ProductoEdicionRequest(1L, 2L, "Portatil Y", "desc", null);
        var edicionVariante = new VarianteEdicionRequest("Mouse v2", new BigDecimal("50000.00"));

        servicio.actualizarProducto(EMPLEADO, 50L, edicionProducto);
        servicio.cambiarEstadoProducto(EMPLEADO, 50L, false);
        servicio.actualizarVariante(EMPLEADO, 7L, edicionVariante);
        servicio.cambiarEstadoVariante(EMPLEADO, 7L, true);

        verify(repo).actualizarProducto(EMPLEADO, 50L, edicionProducto);
        verify(repo).cambiarEstadoProducto(EMPLEADO, 50L, false);
        verify(repo).actualizarVariante(EMPLEADO, 7L, edicionVariante);
        verify(repo).cambiarEstadoVariante(EMPLEADO, 7L, true);
    }

    @Test
    void listarProductosValidaLaPaginaYElTamanoAntesDeTocarLaBase() {
        for (int[] malo : new int[][] {{-1, 20}, {0, 0}, {0, 51}, {100_001, 20}}) {
            assertThatThrownBy(() -> servicio.listarProductos(EMPLEADO, malo[0], malo[1]))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.VALIDACION);
        }
        servicio.listarProductos(EMPLEADO, 0, 1);
        servicio.listarProductos(EMPLEADO, 100_000, 50);

        verify(repo).listarProductos(EMPLEADO, 0, 1);
        verify(repo).listarProductos(EMPLEADO, 100_000, 50);
        org.mockito.Mockito.verifyNoMoreInteractions(repo);
    }

    // ---------------- Imagen del producto ----------------

    @Test
    void crearProductoGuardaLaUrlYaValidadaYNormalizada() {
        var req = new ProductoRequest(1L, 2L, "Portatil X", null, "  https://x/y.png ", List.of(VARIANTE));
        var normalizada = new ProductoRequest(1L, 2L, "Portatil X", null, "https://x/y.png", List.of(VARIANTE));
        when(imagenes.validarUrl("  https://x/y.png ")).thenReturn("https://x/y.png");
        when(repo.crearProductoConVariantes(EMPLEADO, normalizada)).thenReturn(new ProductoCreadoResponse(50L, List.of(100L)));

        assertThat(servicio.crearProducto(EMPLEADO, req).idProducto()).isEqualTo(50L);
    }

    @Test
    void editarUnProductoPasaLaUrlValidadaYSinUrlLaQuita() {
        when(imagenes.validarUrl("https://x/y.png")).thenReturn("https://x/y.png");

        servicio.actualizarProducto(EMPLEADO, 50L, new ProductoEdicionRequest(1L, 2L, "Portatil Y", null, "https://x/y.png"));
        servicio.actualizarProducto(EMPLEADO, 51L, new ProductoEdicionRequest(1L, 2L, "Otro", null, null));

        verify(repo).actualizarProducto(EMPLEADO, 50L, new ProductoEdicionRequest(1L, 2L, "Portatil Y", null, "https://x/y.png"));
        verify(repo).actualizarProducto(EMPLEADO, 51L, new ProductoEdicionRequest(1L, 2L, "Otro", null, null));
    }

    @Test
    void unaUrlInvalidaFrenaLaOperacionAntesDeTocarLaBase() {
        when(imagenes.validarUrl("https://evil.example.com/a.png"))
                .thenThrow(new NegocioException(CodigoError.IMAGEN_URL_INVALIDA, "La imagen debe estar en tu almacenamiento"));

        assertThatThrownBy(() -> servicio.crearProducto(EMPLEADO,
                new ProductoRequest(1L, 2L, "X", null, "https://evil.example.com/a.png", List.of(VARIANTE))))
                .isInstanceOf(NegocioException.class);
        assertThatThrownBy(() -> servicio.actualizarProducto(EMPLEADO, 50L,
                new ProductoEdicionRequest(1L, 2L, "X", null, "https://evil.example.com/a.png")))
                .isInstanceOf(NegocioException.class);

        verifyNoInteractions(repo);
    }
}
