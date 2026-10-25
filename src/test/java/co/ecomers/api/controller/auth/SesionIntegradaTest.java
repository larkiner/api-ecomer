package co.ecomers.api.controller.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.backoffice.ReporteController;
import co.ecomers.api.controller.backoffice.TrabajadorController;
import co.ecomers.api.controller.tienda.CarritoController;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.Credencial;
import co.ecomers.api.repository.backoffice.EmpleadoAuthRepository;
import co.ecomers.api.repository.backoffice.TrabajadorRepository;
import co.ecomers.api.security.InvalidacionSesionService;
import co.ecomers.api.security.LimiteIntentosLogin;
import co.ecomers.api.service.auth.CredencialService;
import co.ecomers.api.service.auth.JwtService;
import co.ecomers.api.service.auth.PasswordService;
import co.ecomers.api.service.auth.RegistroService;
import co.ecomers.api.service.auth.SesionService;
import co.ecomers.api.service.backoffice.ReporteService;
import co.ecomers.api.service.backoffice.TrabajadorService;
import co.ecomers.api.service.tienda.CarritoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * El recorrido completo con la seguridad REAL (firma, vigencia, revocacion y roles): login → usar el token →
 * logout → el token ya no sirve. Solo la base (credenciales) y la logica de carrito/reportes van simuladas.
 */
@PruebaWeb({AuthController.class, CarritoController.class, ReporteController.class, TrabajadorController.class})
@Import({JwtService.class, SesionService.class, TrabajadorService.class, LimiteIntentosLogin.class})
class SesionIntegradaTest {


    @Autowired MockMvc mvc;
    @Autowired InvalidacionSesionService invalidacion;
    @MockitoBean CredencialService credenciales;
    @MockitoBean EmpleadoAuthRepository empleadosRepo;
    @MockitoBean RegistroService registro;
    @MockitoBean PasswordService password;
    @MockitoBean CarritoService carrito;
    @MockitoBean ReporteService reportes;
    @MockitoBean TrabajadorRepository trabajadores;

    private String iniciarSesion(Credencial cuenta) throws Exception {
        when(credenciales.autenticar(cuenta.email(), "Tienda!2026")).thenReturn(cuenta);
        String cuerpo = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + cuenta.email() + "\",\"password\":\"Tienda!2026\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(cuerpo, "$.token");
    }

    private static final Credencial ANA = new Credencial(7L, "ana@correo.com", "h", true, "CLIENTE");
    private static final Credencial BODEGA = new Credencial(3L, "bodega@ecomers.co", "h", true, "EMPLEADO");

    @Test
    void elTokenDelLoginDaAccesoYIdentificaAlCliente() throws Exception {
        String token = iniciarSesion(ANA);

        mvc.perform(delete("/api/v1/carrito").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        verify(carrito).vaciar(new ClienteActual(7L, "ana@correo.com"));
    }

    @Test
    void despuesDelLogoutElMismoTokenDa401() throws Exception {
        String token = iniciarSesion(ANA);
        mvc.perform(delete("/api/v1/carrito").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());

        mvc.perform(delete("/api/v1/carrito").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void elRolDelTokenDecideElAcceso() throws Exception {
        String cliente = iniciarSesion(ANA);
        String empleado = iniciarSesion(BODEGA);

        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").header("Authorization", "Bearer " + cliente))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").header("Authorization", "Bearer " + empleado))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/carrito").header("Authorization", "Bearer " + empleado))
                .andExpect(status().isForbidden());
    }

    @Test
    void unTokenManipuladoDa401() throws Exception {
        String token = iniciarSesion(ANA);
        String[] partes = token.split("\\.");
        // Cambiar el rol en el cuerpo sin volver a firmar
        String cuerpo = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new String(java.util.Base64.getUrlDecoder().decode(partes[1]))
                        .replace("CLIENTE", "EMPLEADO").getBytes());
        String falso = partes[0] + "." + cuerpo + "." + partes[2];

        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").header("Authorization", "Bearer " + falso))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elTokenEmitidoAntesDeCambiarLaPasswordYaNoSirve() throws Exception {
        String token = iniciarSesion(ANA);
        mvc.perform(delete("/api/v1/carrito").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());

        // El corte de invalidacion se trunca al segundo: se espera a que cambie el segundo del "iat" del token.
        Thread.sleep(1100);
        invalidacion.invalidarSesionesDe("ana@correo.com");

        mvc.perform(delete("/api/v1/carrito").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    private static final Credencial ADMIN = new Credencial(1L, "admin@ecomers.co", "h", true, "ADMIN");

    @Test
    void alQuitarleUnPermisoAUnTrabajadorSuTokenDejaDeServirPeroElDelAdminSigue() throws Exception {
        when(empleadosRepo.permisosDe(3L)).thenReturn(java.util.List.of("PRODUCTOS"));
        when(empleadosRepo.permisosDe(1L)).thenReturn(java.util.List.of("CATEGORIAS", "PRODUCTOS"));
        when(trabajadores.quitarPermiso(any(), eq(3L), eq("PRODUCTOS"))).thenReturn("bodega@ecomers.co");
        String trabajador = iniciarSesion(BODEGA);
        String admin = iniciarSesion(ADMIN);
        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").header("Authorization", "Bearer " + trabajador))
                .andExpect(status().isOk());

        // El corte de invalidacion se trunca al segundo: se espera a que cambie el segundo del "iat" del token.
        Thread.sleep(1100);
        mvc.perform(delete("/api/v1/backoffice/trabajadores/3/permisos/PRODUCTOS")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").header("Authorization", "Bearer " + trabajador))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/backoffice/reportes/stock-bajo").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void elTokenDeUnAdminAbreLasRutasDeTrabajadoresYElDeUnTrabajadorNo() throws Exception {
        when(empleadosRepo.permisosDe(1L)).thenReturn(java.util.List.of("CATEGORIAS", "PRODUCTOS"));
        when(empleadosRepo.permisosDe(3L)).thenReturn(java.util.List.of("CATEGORIAS", "PRODUCTOS"));
        String admin = iniciarSesion(ADMIN);
        String trabajador = iniciarSesion(BODEGA);

        mvc.perform(get("/api/v1/backoffice/trabajadores").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/trabajadores").header("Authorization", "Bearer " + trabajador))
                .andExpect(status().isForbidden());
    }
}
