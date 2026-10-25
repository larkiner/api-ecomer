package co.ecomers.api.controller.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.Tokens;
import co.ecomers.api.dto.auth.AuthResponse;
import co.ecomers.api.dto.auth.CambioPasswordRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.service.auth.PasswordService;
import co.ecomers.api.service.auth.RegistroService;
import co.ecomers.api.service.auth.SesionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@PruebaWeb(AuthController.class)
class AuthControllerTest {

    private static final String REGISTRO = """
            {"nombre":"Ana Pérez","email":"ana@correo.com","tipoDocumento":"CC","numeroDocumento":"1020304050",
             "telefono":"3001234567","password":"Tienda!2026"}""";

    @Autowired MockMvc mvc;
    @MockitoBean RegistroService registro;
    @MockitoBean SesionService sesion;
    @MockitoBean PasswordService password;

    private static AuthResponse respuesta() {
        return AuthResponse.bearer("jwt-firmado", 3600, "ana@correo.com", "CLIENTE");
    }

    // ---------------- Registro ----------------

    @Test
    void registroEsPublicoYDevuelve201ConElToken() throws Exception {
        when(registro.registrar(any())).thenReturn(respuesta());

        mvc.perform(post("/api/v1/auth/registro").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("jwt-firmado"))
                .andExpect(jsonPath("$.tipo").value("Bearer"))
                .andExpect(jsonPath("$.expiraEnSegundos").value(3600))
                .andExpect(jsonPath("$.email").value("ana@correo.com"))
                .andExpect(jsonPath("$.rol").value("CLIENTE"));
    }

    @Test
    void registroConDatosInvalidosDa400ConLosCampos() throws Exception {
        String malo = """
                {"nombre":"","email":"no-es-correo","tipoDocumento":"1","numeroDocumento":"1",
                 "telefono":"123","password":"corta"}""";

        mvc.perform(post("/api/v1/auth/registro").contentType(MediaType.APPLICATION_JSON).content(malo))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"))
                .andExpect(jsonPath("$.errores.length()").value(6));
        verifyNoInteractions(registro);
    }

    @Test
    void elTelefonoDebeTenerExactamenteDiezDigitos() throws Exception {
        mvc.perform(post("/api/v1/auth/registro").contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRO.replace("3001234567", "300123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("telefono"));
    }

    @Test
    void correoYaRegistradoDa409() throws Exception {
        when(registro.registrar(any())).thenThrow(new NegocioException(CodigoError.EMAIL_YA_REGISTRADO, "Ya existe una cuenta con ese correo"));

        mvc.perform(post("/api/v1/auth/registro").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.codigo").value("EMAIL_YA_REGISTRADO"));
    }

    @Test
    void contrasenaDebilDa400() throws Exception {
        when(registro.registrar(any())).thenThrow(new NegocioException(CodigoError.PASSWORD_DEBIL, "demasiado débil"));

        mvc.perform(post("/api/v1/auth/registro").contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("PASSWORD_DEBIL"));
    }

    // ---------------- Login ----------------

    @Test
    void loginEsPublicoYDevuelveElToken() throws Exception {
        when(sesion.login(any())).thenReturn(respuesta());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.com\",\"password\":\"Tienda!2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-firmado"));
    }

    @Test
    void unTokenViejoEnLaCabeceraNoImpideIniciarSesion() throws Exception {
        when(sesion.login(any())).thenReturn(respuesta());

        mvc.perform(post("/api/v1/auth/login").header("Authorization", "Bearer token.vencido.o-revocado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.com\",\"password\":\"Tienda!2026\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void credencialesInvalidasDan401ConMensajeGenerico() throws Exception {
        when(sesion.login(any())).thenThrow(new NegocioException(CodigoError.CREDENCIALES_INVALIDAS, "Email o contraseña incorrectos"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.com\",\"password\":\"mala\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("CREDENCIALES_INVALIDAS"));
    }

    @Test
    void cuentaInactivaDa403() throws Exception {
        when(sesion.login(any())).thenThrow(new NegocioException(CodigoError.CUENTA_INACTIVA, "La cuenta está inactiva"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@correo.com\",\"password\":\"Tienda!2026\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.codigo").value("CUENTA_INACTIVA"));
    }

    @Test
    void loginConCuerpoInvalidoDa400() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("VALIDACION"));
        verifyNoInteractions(sesion);
    }

    // ---------------- Logout ----------------

    @Test
    void logoutSinTokenDa401() throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
        verifyNoInteractions(sesion);
    }

    @Test
    void logoutRevocaElTokenActualYDa204() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(Tokens.cliente())).andExpect(status().isNoContent());

        verify(sesion).cerrarSesion(eq("jti-cliente"), any());
    }

    @Test
    void tambienElEmpleadoPuedeCerrarSesion() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(Tokens.empleado())).andExpect(status().isNoContent());

        verify(sesion).cerrarSesion(eq("jti-empleado"), any());
    }

    // ---------------- Cambio de contrasena ----------------

    @Test
    void cambiarPasswordSinTokenDa401() throws Exception {
        mvc.perform(put("/api/v1/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwordActual\":\"Tienda!2026\",\"passwordNueva\":\"Nueva!Clave7\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(password);
    }

    @Test
    void cambiarPasswordUsaElCorreoDelToken() throws Exception {
        mvc.perform(put("/api/v1/auth/password").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwordActual\":\"Tienda!2026\",\"passwordNueva\":\"Nueva!Clave7\"}"))
                .andExpect(status().isNoContent());

        verify(password).cambiarPassword("ana@correo.com", new CambioPasswordRequest("Tienda!2026", "Nueva!Clave7"));
    }

    @Test
    void laNuevaPasswordCortaDa400() throws Exception {
        mvc.perform(put("/api/v1/auth/password").with(Tokens.cliente()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passwordActual\":\"Tienda!2026\",\"passwordNueva\":\"corta\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(password);
    }

    @Test
    void rutasDeAuthNoDeclaradasQuedanDenegadas() throws Exception {
        mvc.perform(post("/api/v1/auth/admin").with(Tokens.cliente())).andExpect(status().isForbidden());
    }
}
