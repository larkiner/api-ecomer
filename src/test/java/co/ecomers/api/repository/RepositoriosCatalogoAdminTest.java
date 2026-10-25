package co.ecomers.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.backoffice.CategoriaResponse;
import co.ecomers.api.dto.backoffice.ImpuestoResponse;
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

/** Cada metodo llama a la rutina de PKG_CATALOGO con el ID del empleado primero y su usuario de Oracle. */
class RepositoriosCatalogoAdminTest {

    private static final String IMAGEN = "https://abc.supabase.co/storage/v1/object/public/productos/productos/audio.webp";
    private static final EmpleadoActual EMPLEADO = new EmpleadoActual(3L, "ana.bodega@ecomers.co");

    private final DbFalsa<BaseDatosEmpleado> f = DbFalsa.de(BaseDatosEmpleado.class);
    private final CatalogoAdminRepository repo = new CatalogoAdminRepository(f.db);

    // ---------------- Lecturas ----------------

    @Test
    @SuppressWarnings("unchecked")
    void listarCategoriasMapeaElPadreNuloElEstadoYLaImagen() throws Exception {
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_CATALOGO.listar_categorias"), mapper.capture(), eq(3L)))
                .thenAnswer(i -> List.of(
                        mapper.getValue().mapRow(categoria(10L, 0L, true, "Portatiles", 1, IMAGEN), 0),
                        mapper.getValue().mapRow(categoria(11L, 10L, false, "Gamer", 0, null), 1)));

        List<CategoriaResponse> lista = repo.listarCategorias(EMPLEADO);

        assertThat(lista).containsExactly(
                new CategoriaResponse(10L, null, "Portatiles", true, IMAGEN),
                new CategoriaResponse(11L, 10L, "Gamer", false, null));
        verify(f.db).lectura(eq("emp:ana.bodega@ecomers.co"), any());
    }

    private static ResultSet categoria(long id, long padre, boolean sinPadre, String nombre, int activo, String imagen)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("ID_CATEGORIA")).thenReturn(id);
        when(rs.getLong("ID_CATEGORIA_PADRE")).thenReturn(padre);
        when(rs.wasNull()).thenReturn(sinPadre);
        when(rs.getString("NOMBRE")).thenReturn(nombre);
        when(rs.getInt("ACTIVO")).thenReturn(activo);
        when(rs.getString("IMAGEN_URL")).thenReturn(imagen);
        return rs;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listarImpuestosMapeaLaTasaComoBigDecimal() throws Exception {
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_CATALOGO.listar_impuestos"), mapper.capture(), eq(3L)))
                .thenAnswer(i -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("ID_IMPUESTO")).thenReturn(1L);
                    when(rs.getString("NOMBRE")).thenReturn("IVA 19%");
                    when(rs.getBigDecimal("TASA")).thenReturn(new BigDecimal("0.1900"));
                    when(rs.getInt("ACTIVO")).thenReturn(1);
                    return List.of(mapper.getValue().mapRow(rs, 0));
                });

        assertThat(repo.listarImpuestos(EMPLEADO))
                .containsExactly(new ImpuestoResponse(1L, "IVA 19%", new BigDecimal("0.1900"), true));
    }

    // ---------------- Categorias ----------------

    @Test
    void crearCategoriaPasaElPadreONuloYDevuelveElId() {
        when(f.sesion.funcionNumero(eq("ECOMERS.PKG_CATALOGO.crear_categoria"), any(), any(), any())).thenReturn(20L);

        assertThat(repo.crearCategoria(EMPLEADO, "Portatiles", null, null)).isEqualTo(20L);
        assertThat(repo.crearCategoria(EMPLEADO, "Gamer", 10L, null)).isEqualTo(20L);

        verify(f.sesion).funcionNumero("ECOMERS.PKG_CATALOGO.crear_categoria", 3L, "Portatiles", null);
        verify(f.sesion).funcionNumero("ECOMERS.PKG_CATALOGO.crear_categoria", 3L, "Gamer", 10L);
        verify(f.db, times(2)).enTransaccion(eq("emp:ana.bodega@ecomers.co"), any());
        verify(f.sesion, never()).procedimiento(eq("ECOMERS.PKG_CATALOGO.fijar_imagen_categoria"), any(), any(), any());
    }

    @Test
    void conImagenSeFijaDentroDeLaMismaTransaccionTrasCrearLaCategoria() {
        when(f.sesion.funcionNumero(eq("ECOMERS.PKG_CATALOGO.crear_categoria"), any(), any(), any())).thenReturn(21L);

        assertThat(repo.crearCategoria(EMPLEADO, "Audio", null, IMAGEN)).isEqualTo(21L);

        verify(f.db, times(1)).enTransaccion(eq("emp:ana.bodega@ecomers.co"), any());
        var orden = inOrder(f.sesion);
        orden.verify(f.sesion).funcionNumero("ECOMERS.PKG_CATALOGO.crear_categoria", 3L, "Audio", null);
        orden.verify(f.sesion).procedimiento("ECOMERS.PKG_CATALOGO.fijar_imagen_categoria", 3L, 21L, IMAGEN);
    }

    @Test
    void actualizarYCambiarEstadoDeCategoriaLlamanASusProcedimientos() {
        repo.actualizarCategoria(EMPLEADO, 20L, "Portatiles pro", 10L, IMAGEN);
        repo.cambiarEstadoCategoria(EMPLEADO, 20L, false);
        repo.cambiarEstadoCategoria(EMPLEADO, 20L, true);

        verify(f.db, times(3)).enTransaccionSinResultado(eq("emp:ana.bodega@ecomers.co"), any(Consumer.class));
        verify(f.sesion).procedimiento("ECOMERS.PKG_CATALOGO.actualizar_categoria", 3L, 20L, "Portatiles pro", 10L);
        verify(f.sesion).procedimiento("ECOMERS.PKG_CATALOGO.fijar_imagen_categoria", 3L, 20L, IMAGEN);
        verify(f.sesion).procedimiento("ECOMERS.PKG_CATALOGO.cambiar_estado_categoria", 3L, 20L, 0);
        verify(f.sesion).procedimiento("ECOMERS.PKG_CATALOGO.cambiar_estado_categoria", 3L, 20L, 1);
    }

    @Test
    void editarUnaCategoriaSinImagenLaQuitaPorqueElPutReemplazaTodo() {
        repo.actualizarCategoria(EMPLEADO, 21L, "Audio", null, null);

        verify(f.sesion).procedimiento("ECOMERS.PKG_CATALOGO.fijar_imagen_categoria", 3L, 21L, null);
    }
}
