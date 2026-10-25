package co.ecomers.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.backoffice.TrabajadorResponse;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.TrabajadorRepository;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;

/** Cada metodo llama a la rutina de PKG_EMPLEADO con el ID del admin primero y el usuario de Oracle correcto. */
class RepositoriosTrabajadorTest {

    private static final EmpleadoActual ADMIN = new EmpleadoActual(1L, "admin@ecomers.co");

    private final DbFalsa<BaseDatosEmpleado> f = DbFalsa.de(BaseDatosEmpleado.class);
    private final TrabajadorRepository repo = new TrabajadorRepository(f.db);

    @Test
    void crearLlamaACrearTrabajadorYDevuelveElId() {
        when(f.sesion.funcionNumero(eq("ECOMERS.PKG_EMPLEADO.crear_trabajador"), any(), any(), any(), any()))
                .thenReturn(9L);

        long id = repo.crear(ADMIN, "Ana Bodega", "ana@ecomers.co", "$2a$hash");

        assertThat(id).isEqualTo(9L);
        verify(f.db).enTransaccion(eq("emp:admin@ecomers.co"), any());
        verify(f.sesion).funcionNumero("ECOMERS.PKG_EMPLEADO.crear_trabajador", 1L, "Ana Bodega", "ana@ecomers.co", "$2a$hash");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listarMapeaLasFilasYSeparaLosPermisos() throws Exception {
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_EMPLEADO.listar_trabajadores"), mapper.capture(), eq(1L)))
                .thenAnswer(i -> List.of(
                        mapper.getValue().mapRow(fila(5L, "Ana Bodega", "CATEGORIAS,PRODUCTOS"), 0),
                        mapper.getValue().mapRow(fila(6L, "Beto Ventas", null), 1)));

        List<TrabajadorResponse> lista = repo.listar(ADMIN);

        assertThat(lista).containsExactly(
                new TrabajadorResponse(5L, "Ana Bodega", "5@ecomers.co", true,
                        OffsetDateTime.parse("2026-10-21T10:00:00-05:00"), List.of("CATEGORIAS", "PRODUCTOS")),
                new TrabajadorResponse(6L, "Beto Ventas", "6@ecomers.co", true,
                        OffsetDateTime.parse("2026-10-21T10:00:00-05:00"), List.of()));
        verify(f.db).lectura(eq("emp:admin@ecomers.co"), any());
    }

    private static ResultSet fila(long id, String nombre, String permisos) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("ID_EMPLEADO")).thenReturn(id);
        when(rs.getString("NOMBRE")).thenReturn(nombre);
        when(rs.getString("EMAIL")).thenReturn(id + "@ecomers.co");
        when(rs.getInt("ACTIVO")).thenReturn(1);
        when(rs.getObject("FECHA_CREACION", LocalDateTime.class)).thenReturn(LocalDateTime.of(2026, 10, 21, 10, 0));
        when(rs.getString("PERMISOS")).thenReturn(permisos);
        return rs;
    }

    @Test
    void cambiarEstadoEnviaUnoOCeroYDevuelveElCorreo() {
        when(f.sesion.funcionTexto(eq("ECOMERS.PKG_EMPLEADO.cambiar_estado"), any(), any(), any()))
                .thenReturn("ana@ecomers.co");

        assertThat(repo.cambiarEstado(ADMIN, 5L, false)).isEqualTo("ana@ecomers.co");
        assertThat(repo.cambiarEstado(ADMIN, 5L, true)).isEqualTo("ana@ecomers.co");

        verify(f.sesion).funcionTexto("ECOMERS.PKG_EMPLEADO.cambiar_estado", 1L, 5L, 0);
        verify(f.sesion).funcionTexto("ECOMERS.PKG_EMPLEADO.cambiar_estado", 1L, 5L, 1);
        verify(f.db, org.mockito.Mockito.times(2)).enTransaccion(eq("emp:admin@ecomers.co"), any());
    }

    @Test
    void asignarYQuitarPermisoLlamanASusRutinasYDevuelvenElCorreo() {
        when(f.sesion.funcionTexto(eq("ECOMERS.PKG_EMPLEADO.asignar_permiso"), any(), any(), any()))
                .thenReturn("ana@ecomers.co");
        when(f.sesion.funcionTexto(eq("ECOMERS.PKG_EMPLEADO.quitar_permiso"), any(), any(), any()))
                .thenReturn("ana@ecomers.co");

        assertThat(repo.asignarPermiso(ADMIN, 5L, "PRODUCTOS")).isEqualTo("ana@ecomers.co");
        assertThat(repo.quitarPermiso(ADMIN, 5L, "PRODUCTOS")).isEqualTo("ana@ecomers.co");

        verify(f.sesion).funcionTexto("ECOMERS.PKG_EMPLEADO.asignar_permiso", 1L, 5L, "PRODUCTOS");
        verify(f.sesion).funcionTexto("ECOMERS.PKG_EMPLEADO.quitar_permiso", 1L, 5L, "PRODUCTOS");
    }
}
