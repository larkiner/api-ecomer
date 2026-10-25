package co.ecomers.api.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.dto.auth.CambioPasswordRequest;
import co.ecomers.api.dto.auth.LoginRequest;
import co.ecomers.api.dto.auth.RegistroRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.Credencial;
import co.ecomers.api.model.NuevoCliente;
import co.ecomers.api.repository.backoffice.EmpleadoAuthRepository;
import co.ecomers.api.repository.tienda.ClienteAuthRepository;
import co.ecomers.api.security.InvalidacionSesionService;
import co.ecomers.api.security.TokenBlacklistService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Registro, login, logout y cambio de contrasena con repositorios simulados y BCrypt real. */
class AuthServiciosTest {

    private static final String PASSWORD = "Tienda!2026";

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final ClienteAuthRepository clientes = mock(ClienteAuthRepository.class);
    private final EmpleadoAuthRepository empleados = mock(EmpleadoAuthRepository.class);
    private final InvalidacionSesionService invalidacion = mock(InvalidacionSesionService.class);
    private final TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
    private final JwtEncoder jwtEncoder = mock(JwtEncoder.class);

    private final CredencialService credenciales = new CredencialService(clientes, empleados, encoder);
    private final JwtService jwt = new JwtService(jwtEncoder, 3_600_000, "ecomers-api");
    private final SesionService sesion = new SesionService(credenciales, jwt, blacklist, empleados);
    private final RegistroService registro = new RegistroService(clientes, encoder, sesion);
    private final PasswordService password =
            new PasswordService(credenciales, clientes, empleados, invalidacion, encoder);

    private Credencial cliente(boolean activo) {
        return new Credencial(7L, "ana@correo.com", encoder.encode(PASSWORD), activo, "CLIENTE");
    }

    private Credencial empleado() {
        return new Credencial(3L, "bodega@ecomers.co", encoder.encode(PASSWORD), true, "EMPLEADO");
    }

    private void tokenEmitido() {
        var tokenFalso = Jwt.withTokenValue("token-firmado").header("alg", "HS256").subject("x").build();
        when(jwtEncoder.encode(any(JwtEncoderParameters.class))).thenReturn(tokenFalso);
    }

    private static void esperaError(Runnable accion, CodigoError esperado) {
        assertThatThrownBy(accion::run).isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(esperado);
    }

    // ---------------- Login ----------------

    @Test
    void loginDeUnClienteDevuelveElTokenConSuRol() {
        tokenEmitido();
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(true)));

        var r = sesion.login(new LoginRequest("  Ana@Correo.com ", PASSWORD));

        assertThat(r.token()).isEqualTo("token-firmado");
        assertThat(r.tipo()).isEqualTo("Bearer");
        assertThat(r.expiraEnSegundos()).isEqualTo(3600);
        assertThat(r.email()).isEqualTo("ana@correo.com");
        assertThat(r.rol()).isEqualTo("CLIENTE");
        verifyNoInteractions(empleados);
    }

    @Test
    void siNoEsClienteSeBuscaEntreLosEmpleados() {
        tokenEmitido();
        when(clientes.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.empty());
        when(empleados.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.of(empleado()));

        assertThat(sesion.login(new LoginRequest("bodega@ecomers.co", PASSWORD)).rol()).isEqualTo("EMPLEADO");
    }

    /** Claims del token que se firmo en la ultima llamada al encoder. */
    private java.util.Map<String, Object> clavesEmitidas() {
        var parametros = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(parametros.capture());
        return parametros.getValue().getClaims().getClaims();
    }

    @Test
    void elLoginDeUnEmpleadoCargaSusPermisosYLosEscribeEnElToken() {
        tokenEmitido();
        when(clientes.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.empty());
        when(empleados.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.of(empleado()));
        when(empleados.permisosDe(3L)).thenReturn(List.of("CATEGORIAS", "PRODUCTOS"));

        sesion.login(new LoginRequest("bodega@ecomers.co", PASSWORD));

        var claims = clavesEmitidas();
        assertThat(claims.get("permisos")).isEqualTo(List.of("CATEGORIAS", "PRODUCTOS"));
        assertThat(claims.get("id_empleado")).isEqualTo(3L);
    }

    @Test
    void elLoginDeUnAdminTambienCargaSusPermisos() {
        tokenEmitido();
        var admin = new Credencial(1L, "admin@ecomers.co", encoder.encode(PASSWORD), true, Credencial.ROL_ADMIN);
        when(clientes.buscarPorEmail("admin@ecomers.co")).thenReturn(Optional.empty());
        when(empleados.buscarPorEmail("admin@ecomers.co")).thenReturn(Optional.of(admin));
        when(empleados.permisosDe(1L)).thenReturn(List.of("CATEGORIAS", "PRODUCTOS"));

        var respuesta = sesion.login(new LoginRequest("admin@ecomers.co", PASSWORD));

        assertThat(respuesta.rol()).isEqualTo("ADMIN");
        assertThat(clavesEmitidas().get("permisos")).isEqualTo(List.of("CATEGORIAS", "PRODUCTOS"));
    }

    @Test
    void elLoginDeUnClienteNoConsultaPermisos() {
        tokenEmitido();
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(true)));

        sesion.login(new LoginRequest("ana@correo.com", PASSWORD));

        verify(empleados, never()).permisosDe(anyLong());
        assertThat(clavesEmitidas()).doesNotContainKey("permisos");
    }

    @Test
    void unEmpleadoSinPermisosRecibeUnTokenConLaListaVacia() {
        tokenEmitido();
        when(clientes.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.empty());
        when(empleados.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.of(empleado()));
        when(empleados.permisosDe(3L)).thenReturn(List.of());

        sesion.login(new LoginRequest("bodega@ecomers.co", PASSWORD));

        assertThat(clavesEmitidas().get("permisos")).isEqualTo(List.of());
    }

    @Test
    void contrasenaIncorrectaYCorreoInexistenteDanLaMismaRespuesta() {
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(true)));
        when(clientes.buscarPorEmail("nadie@correo.com")).thenReturn(Optional.empty());
        when(empleados.buscarPorEmail(anyString())).thenReturn(Optional.empty());

        esperaError(() -> sesion.login(new LoginRequest("ana@correo.com", "otra-clave-9")), CodigoError.CREDENCIALES_INVALIDAS);
        esperaError(() -> sesion.login(new LoginRequest("nadie@correo.com", PASSWORD)), CodigoError.CREDENCIALES_INVALIDAS);
        assertThatThrownBy(() -> sesion.login(new LoginRequest("nadie@correo.com", PASSWORD)))
                .hasMessage(CredencialService.credencialesInvalidas().getMessage());
    }

    @Test
    void cuentaInactivaConLaClaveCorrectaDa403() {
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(false)));

        esperaError(() -> sesion.login(new LoginRequest("ana@correo.com", PASSWORD)), CodigoError.CUENTA_INACTIVA);
    }

    @Test
    void cuentaInactivaConLaClaveIncorrectaNoRevelaQueExiste() {
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(false)));

        esperaError(() -> sesion.login(new LoginRequest("ana@correo.com", "otra-clave-9")), CodigoError.CREDENCIALES_INVALIDAS);
    }

    // ---------------- Logout ----------------

    @Test
    void logoutRevocaElJtiHastaQueVenzaElToken() {
        Instant vence = Instant.now().plusSeconds(600);

        sesion.cerrarSesion("jti-1", vence);

        verify(blacklist).revocar("jti-1", vence);
    }

    @Test
    void logoutSinJtiNoHaceNada() {
        sesion.cerrarSesion(null, Instant.now());
        verify(blacklist, never()).revocar(anyString(), any());
    }

    // ---------------- Registro ----------------

    private static RegistroRequest solicitud(String password) {
        return new RegistroRequest("  Ana Pérez ", " Ana@Correo.com ", "cc", " 1020304050 ", "3001234567", password);
    }

    @Test
    void registroCreaElClienteConElHashYDejaLaSesionIniciada() {
        tokenEmitido();
        when(clientes.registrar(any())).thenReturn(7L);

        var r = registro.registrar(solicitud(PASSWORD));

        var captor = ArgumentCaptor.forClass(NuevoCliente.class);
        verify(clientes).registrar(captor.capture());
        NuevoCliente nuevo = captor.getValue();
        assertThat(nuevo.nombre()).isEqualTo("Ana Pérez");
        assertThat(nuevo.email()).isEqualTo("ana@correo.com");
        assertThat(nuevo.tipoDocumento()).isEqualTo("CC");
        assertThat(nuevo.numeroDocumento()).isEqualTo("1020304050");
        assertThat(nuevo.telefono()).isEqualTo("3001234567");
        assertThat(nuevo.passwordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(encoder.matches(PASSWORD, nuevo.passwordHash())).isTrue();
        assertThat(nuevo.toString()).doesNotContain(nuevo.passwordHash());
        assertThat(r.token()).isEqualTo("token-firmado");
        assertThat(r.rol()).isEqualTo("CLIENTE");
        assertThat(r.email()).isEqualTo("ana@correo.com");
    }

    @Test
    void registroConContrasenaDebilNoTocaLaBase() {
        for (String debil : new String[] {"12345678", "aaaaaaaa", "abcdefgh", "87654321", "Password", "password1", "ecomers123"}) {
            esperaError(() -> registro.registrar(solicitud(debil)), CodigoError.PASSWORD_DEBIL);
        }
        verifyNoInteractions(clientes);
    }

    @Test
    void emailRepetidoLoRechazaLaBaseConConflicto() {
        when(clientes.registrar(any())).thenThrow(new NegocioException(CodigoError.EMAIL_YA_REGISTRADO, "ORA-20300", 20300));

        esperaError(() -> registro.registrar(solicitud(PASSWORD)), CodigoError.EMAIL_YA_REGISTRADO);
    }

    // ---------------- Cambio de contrasena ----------------

    @Test
    void cambiarLaPasswordDeUnClienteGuardaElNuevoHashYCierraSusSesiones() {
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(true)));

        password.cambiarPassword("ana@correo.com", new CambioPasswordRequest(PASSWORD, "Nueva!Clave7"));

        var hash = ArgumentCaptor.forClass(String.class);
        verify(clientes).cambiarPassword(org.mockito.ArgumentMatchers.eq(7L), hash.capture());
        assertThat(encoder.matches("Nueva!Clave7", hash.getValue())).isTrue();
        verify(invalidacion).invalidarSesionesDe("ana@correo.com");
        verifyNoInteractions(empleados);
    }

    @Test
    void cambiarLaPasswordDeUnEmpleadoVaPorSuPropioRepositorio() {
        when(clientes.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.empty());
        when(empleados.buscarPorEmail("bodega@ecomers.co")).thenReturn(Optional.of(empleado()));

        password.cambiarPassword("bodega@ecomers.co", new CambioPasswordRequest(PASSWORD, "Nueva!Clave7"));

        verify(empleados).cambiarPassword(org.mockito.ArgumentMatchers.eq(3L), anyString());
        verify(clientes, never()).cambiarPassword(anyLong(), anyString());
        verify(invalidacion).invalidarSesionesDe("bodega@ecomers.co");
    }

    @Test
    void conLaPasswordActualIncorrectaNoSeCambiaNada() {
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(true)));

        esperaError(() -> password.cambiarPassword("ana@correo.com", new CambioPasswordRequest("equivocada-1", "Nueva!Clave7")),
                CodigoError.CREDENCIALES_INVALIDAS);

        verify(clientes, never()).cambiarPassword(anyLong(), anyString());
        verifyNoInteractions(invalidacion);
    }

    @Test
    void laNuevaPasswordDebilSeRechazaYNoCierraSesiones() {
        when(clientes.buscarPorEmail("ana@correo.com")).thenReturn(Optional.of(cliente(true)));

        esperaError(() -> password.cambiarPassword("ana@correo.com", new CambioPasswordRequest(PASSWORD, "12345678")),
                CodigoError.PASSWORD_DEBIL);

        verify(clientes, never()).cambiarPassword(anyLong(), anyString());
        verifyNoInteractions(invalidacion);
    }

    @Test
    void unTokenDeUnaCuentaQueYaNoExisteNoPuedeCambiarPassword() {
        when(clientes.buscarPorEmail("fantasma@correo.com")).thenReturn(Optional.empty());
        when(empleados.buscarPorEmail("fantasma@correo.com")).thenReturn(Optional.empty());

        esperaError(() -> password.cambiarPassword("fantasma@correo.com", new CambioPasswordRequest(PASSWORD, "Nueva!Clave7")),
                CodigoError.CREDENCIALES_INVALIDAS);
    }

    @Test
    void laCredencialNuncaImprimeElHash() {
        assertThat(cliente(true).toString()).doesNotContain("$2").contains("ana@correo.com");
    }
}
