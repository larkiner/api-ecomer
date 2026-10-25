package co.ecomers.api.controller.backoffice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.Tokens;
import co.ecomers.api.dto.backoffice.CrearTrabajadorRequest;
import co.ecomers.api.dto.backoffice.TrabajadorCreadoResponse;
import co.ecomers.api.dto.backoffice.TrabajadorResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.TrabajadorService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@PruebaWeb(TrabajadorController.class)
class TrabajadorControllerTest {

    private static final EmpleadoActual ADMIN = new EmpleadoActual(Tokens.ID_ADMIN, "admin@ecomers.co");
    private static final String CUERPO = "{\"nombre\":\"Ana Bodega\",\"email\":\"ana@ecomers.co\",\"password\":\"Tienda!2026\"}";
    private static final String BASE = "/api/v1/backoffice/trabajadores";

    @Autowired MockMvc mvc;
    @MockitoBean TrabajadorService servicio;

    // ---------------- Acceso: solo el admin ----------------

    @Test
    void sinTokenDa401() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CUERPO)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void unTrabajadorConCualquierPermisoYUnClienteDan403EnTodasLasRutas() throws Exception {
        for (var token : new org.springframework.test.web.servlet.request.RequestPostProcessor[] {
                Tokens.trabajador(), Tokens.trabajador("CATEGORIAS", "PRODUCTOS"), Tokens.cliente()}) {
            mvc.perform(get(BASE).with(token)).andExpect(status().isForbidden());
            mvc.perform(post(BASE).with(token).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
            mvc.perform(put(BASE + "/5/estado").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"activo\":false}")).andExpect(status().isForbidden());
            mvc.perform(put(BASE + "/5/permisos/PRODUCTOS").with(token)).andExpect(status().isForbidden());
            mvc.perform(delete(BASE + "/5/permisos/PRODUCTOS").with(token)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(servicio);
    }

    // ---------------- Crear y listar ----------------

    @Test
    void elAdminCreaUnTrabajadorYRecibeSuId() throws Exception {
        when(servicio.crear(any(), any())).thenReturn(new TrabajadorCreadoResponse(9L));

        mvc.perform(post(BASE).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idEmpleado").value(9));

        verify(servicio).crear(ADMIN, new CrearTrabajadorRequest("Ana Bodega", "ana@ecomers.co", "Tienda!2026"));
    }

    @Test
    void elAdminListaLosTrabajadoresConSusPermisosYFechasConDesfase() throws Exception {
        when(servicio.listar(ADMIN)).thenReturn(List.of(new TrabajadorResponse(5L, "Ana Bodega", "ana@ecomers.co", true,
                OffsetDateTime.parse("2026-10-21T10:00:00-05:00"), List.of("CATEGORIAS", "PRODUCTOS"))));

        mvc.perform(get(BASE).with(Tokens.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idEmpleado").value(5))
                .andExpect(jsonPath("$[0].activo").value(true))
                .andExpect(jsonPath("$[0].fechaCreacion").value("2026-10-21T10:00:00-05:00"))
                .andExpect(jsonPath("$[0].permisos[1]").value("PRODUCTOS"));
    }

    @Test
    void crearConDatosInvalidosDa400SinLlegarAlServicio() throws Exception {
        for (String malo : new String[] {
                "{\"nombre\":\"\",\"email\":\"ana@ecomers.co\",\"password\":\"Tienda!2026\"}",
                "{\"nombre\":\"Ana\",\"email\":\"no-es-correo\",\"password\":\"Tienda!2026\"}",
                "{\"nombre\":\"Ana\",\"email\":\"ana@ecomers.co\",\"password\":\"corta\"}",
                "{\"nombre\":\"Ana\",\"email\":\"ana@ecomers.co\"}"}) {
            mvc.perform(post(BASE).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(malo))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        verifyNoInteractions(servicio);
    }

    @Test
    void elCorreoRepetidoDa409() throws Exception {
        when(servicio.crear(any(), any()))
                .thenThrow(new NegocioException(CodigoError.EMAIL_YA_REGISTRADO, "Ya existe una cuenta con ese correo", 20300));

        mvc.perform(post(BASE).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("EMAIL_YA_REGISTRADO"));
    }

    // ---------------- Estado y permisos ----------------

    @Test
    void elAdminActivaYDesactivaUnTrabajador() throws Exception {
        mvc.perform(put(BASE + "/5/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":false}")).andExpect(status().isNoContent());
        mvc.perform(put(BASE + "/5/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isNoContent());

        verify(servicio).cambiarEstado(ADMIN, 5L, false);
        verify(servicio).cambiarEstado(ADMIN, 5L, true);
    }

    @Test
    void elEstadoSinElCampoActivoOConUnTipoRaroDa400() throws Exception {
        for (String malo : new String[] {"{}", "{\"activo\":null}", "{\"activo\":\"quizas\"}", ""}) {
            mvc.perform(put(BASE + "/5/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(malo))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(servicio);
    }

    @Test
    void elAdminAsignaYQuitaPermisos() throws Exception {
        mvc.perform(put(BASE + "/5/permisos/PRODUCTOS").with(Tokens.admin())).andExpect(status().isNoContent());
        mvc.perform(delete(BASE + "/5/permisos/PRODUCTOS").with(Tokens.admin())).andExpect(status().isNoContent());

        verify(servicio).asignarPermiso(ADMIN, 5L, "PRODUCTOS");
        verify(servicio).quitarPermiso(ADMIN, 5L, "PRODUCTOS");
    }

    @Test
    void unIdQueNoEsNumeroDa400() throws Exception {
        mvc.perform(put(BASE + "/abc/permisos/PRODUCTOS").with(Tokens.admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
        verifyNoInteractions(servicio);
    }

    // ---------------- Errores de la base ----------------

    @Test
    void gestionarAOtroAdminOASiMismoDa409() throws Exception {
        org.mockito.Mockito.doThrow(new NegocioException(CodigoError.CUENTA_NO_GESTIONABLE, "no es trabajador", 20322))
                .when(servicio).cambiarEstado(ADMIN, 1L, false);

        mvc.perform(put(BASE + "/1/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CUENTA_NO_GESTIONABLE"));
    }

    @Test
    void trabajadorOPermisoInexistenteDan404Y400() throws Exception {
        org.mockito.Mockito.doThrow(new NegocioException(CodigoError.TRABAJADOR_NO_ENCONTRADO, "no existe", 20323))
                .when(servicio).asignarPermiso(ADMIN, 99L, "PRODUCTOS");
        org.mockito.Mockito.doThrow(new NegocioException(CodigoError.PERMISO_INVALIDO, "no existe", 20324))
                .when(servicio).asignarPermiso(ADMIN, 5L, "NO_EXISTE");

        mvc.perform(put(BASE + "/99/permisos/PRODUCTOS").with(Tokens.admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("TRABAJADOR_NO_ENCONTRADO"));
        mvc.perform(put(BASE + "/5/permisos/NO_EXISTE").with(Tokens.admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("PERMISO_INVALIDO"));
    }

    @Test
    void unAdminQueLaBaseYaNoReconoceDa403() throws Exception {
        when(servicio.listar(ADMIN)).thenThrow(new NegocioException(CodigoError.ACCESO_DENEGADO, "no es admin", 20321));

        mvc.perform(get(BASE).with(Tokens.admin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
    }
}
