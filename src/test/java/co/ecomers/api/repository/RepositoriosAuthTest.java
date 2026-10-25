package co.ecomers.api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.ecomers.api.model.Credencial;
import co.ecomers.api.model.NuevoCliente;
import co.ecomers.api.repository.backoffice.EmpleadoAuthRepository;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import co.ecomers.api.repository.tienda.ClienteAuthRepository;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;

/** Los repositorios de cuentas llaman a su paquete, con los argumentos en orden y el usuario de Oracle correcto. */
class RepositoriosAuthTest {

    private static ResultSet fila(String colId, long id, String email, int activo) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(colId)).thenReturn(id);
        when(rs.getString("EMAIL")).thenReturn(email);
        when(rs.getString("PASSWORD_HASH")).thenReturn("$2a$hash");
        when(rs.getInt("ACTIVO")).thenReturn(activo);
        when(rs.getString("ROL")).thenReturn("TRABAJADOR");
        return rs;
    }

    @Test
    void registrarLlamaAPkgClienteYDevuelveElId() {
        var f = DbFalsa.de(BaseDatosCliente.class);
        var repo = new ClienteAuthRepository(f.db);
        when(f.sesion.funcionNumero(eq("ECOMERS.PKG_CLIENTE.registrar"), any(), any(), any(), any(), any(), any()))
                .thenReturn(42L);

        long id = repo.registrar(new NuevoCliente("Ana Pérez", "ana@correo.com", "CC", "1020304050", "3001234567", "$2a$hash"));

        assertThat(id).isEqualTo(42L);
        verify(f.db).enTransaccion(eq("auth:cliente"), any());
        verify(f.sesion).funcionNumero("ECOMERS.PKG_CLIENTE.registrar",
                "Ana Pérez", "ana@correo.com", "CC", "1020304050", "3001234567", "$2a$hash");
    }

    @Test
    @SuppressWarnings("unchecked")
    void credencialDeClienteSeLeeDeLaFuncionDelPaquete() throws Exception {
        var f = DbFalsa.de(BaseDatosCliente.class);
        var repo = new ClienteAuthRepository(f.db);
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_CLIENTE.credencial_por_email"), mapper.capture(), eq("ana@correo.com")))
                .thenAnswer(i -> List.of(mapper.getValue().mapRow(fila("ID_CLIENTE", 7L, "ana@correo.com", 1), 0)));

        assertThat(repo.buscarPorEmail("ana@correo.com")).contains(new Credencial(7L, "ana@correo.com", "$2a$hash", true, "CLIENTE"));
        verify(f.db).lectura(eq("auth:cliente"), any());
    }

    @Test
    void sinFilasNoHayCredencial() {
        var f = DbFalsa.de(BaseDatosCliente.class);
        var repo = new ClienteAuthRepository(f.db);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_CLIENTE.credencial_por_email"), any(), eq("nadie@correo.com")))
                .thenReturn(List.of());

        assertThat(repo.buscarPorEmail("nadie@correo.com")).isEmpty();
    }

    @Test
    void cuentaDesactivadaSeLeeComoInactiva() throws Exception {
        var f = DbFalsa.de(BaseDatosCliente.class);
        var repo = new ClienteAuthRepository(f.db);
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_CLIENTE.credencial_por_email"), mapper.capture(), any()))
                .thenAnswer(i -> List.of(mapper.getValue().mapRow(fila("ID_CLIENTE", 7L, "ana@correo.com", 0), 0)));

        assertThat(repo.buscarPorEmail("ana@correo.com")).get().extracting(Credencial::activo).isEqualTo(false);
    }

    @Test
    void cambiarPasswordDeClienteLlamaAPkgClienteComoElPropioCliente() {
        var f = DbFalsa.de(BaseDatosCliente.class);
        new ClienteAuthRepository(f.db).cambiarPassword(7L, "$2a$nuevo");

        verify(f.db).enTransaccionSinResultado(eq("cli:7"), any(Consumer.class));
        verify(f.sesion).procedimiento("ECOMERS.PKG_CLIENTE.cambiar_password", 7L, "$2a$nuevo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void credencialDeEmpleadoVieneDeSuPaqueteConElUsuarioEmpleado() throws Exception {
        var f = DbFalsa.de(BaseDatosEmpleado.class);
        var repo = new EmpleadoAuthRepository(f.db);
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_EMPLEADO.credencial_por_email"), mapper.capture(), eq("bodega@ecomers.co")))
                .thenAnswer(i -> List.of(mapper.getValue().mapRow(fila("ID_EMPLEADO", 3L, "bodega@ecomers.co", 1), 0)));

        assertThat(repo.buscarPorEmail("bodega@ecomers.co"))
                .contains(new Credencial(3L, "bodega@ecomers.co", "$2a$hash", true, "EMPLEADO"));
        verify(f.db).lectura(eq("auth:empleado"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void elRolAdminDeLaBaseSeLeeComoRolAdmin() throws Exception {
        var f = DbFalsa.de(BaseDatosEmpleado.class);
        var repo = new EmpleadoAuthRepository(f.db);
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_EMPLEADO.credencial_por_email"), mapper.capture(), eq("admin@ecomers.co")))
                .thenAnswer(i -> {
                    ResultSet rs = fila("ID_EMPLEADO", 1L, "admin@ecomers.co", 1);
                    when(rs.getString("ROL")).thenReturn("ADMIN");
                    return List.of(mapper.getValue().mapRow(rs, 0));
                });

        assertThat(repo.buscarPorEmail("admin@ecomers.co"))
                .contains(new Credencial(1L, "admin@ecomers.co", "$2a$hash", true, Credencial.ROL_ADMIN));
    }

    @Test
    @SuppressWarnings("unchecked")
    void permisosDeLlamaAPkgEmpleadoYDevuelveLosCodigos() throws Exception {
        var f = DbFalsa.de(BaseDatosEmpleado.class);
        var repo = new EmpleadoAuthRepository(f.db);
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        when(f.sesion.funcionCursor(eq("ECOMERS.PKG_EMPLEADO.permisos_de"), mapper.capture(), eq(3L)))
                .thenAnswer(i -> {
                    ResultSet a = mock(ResultSet.class);
                    when(a.getString("CODIGO")).thenReturn("CATEGORIAS");
                    ResultSet b = mock(ResultSet.class);
                    when(b.getString("CODIGO")).thenReturn("PRODUCTOS");
                    return List.of(mapper.getValue().mapRow(a, 0), mapper.getValue().mapRow(b, 1));
                });

        assertThat(repo.permisosDe(3L)).containsExactly("CATEGORIAS", "PRODUCTOS");
        verify(f.db).lectura(eq("auth:empleado"), any());
    }

    @Test
    void cambiarPasswordDeEmpleadoLlamaAPkgEmpleado() {
        var f = DbFalsa.de(BaseDatosEmpleado.class);
        new EmpleadoAuthRepository(f.db).cambiarPassword(3L, "$2a$nuevo");

        verify(f.db).enTransaccionSinResultado(eq("emp:3"), any(Consumer.class));
        verify(f.sesion).procedimiento("ECOMERS.PKG_EMPLEADO.cambiar_password", 3L, "$2a$nuevo");
    }
}
