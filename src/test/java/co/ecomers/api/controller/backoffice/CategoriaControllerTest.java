package co.ecomers.api.controller.backoffice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.Tokens;
import co.ecomers.api.dto.backoffice.CategoriaCreadaResponse;
import co.ecomers.api.dto.backoffice.CategoriaRequest;
import co.ecomers.api.dto.backoffice.CategoriaResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.service.backoffice.CatalogoAdminService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@PruebaWeb(CategoriaController.class)
class CategoriaControllerTest {

    private static final String BASE = "/api/v1/backoffice/categorias";
    private static final String CUERPO = "{\"nombre\":\"Portatiles\",\"idPadre\":10}";
    private static final EmpleadoActual ADMIN = new EmpleadoActual(Tokens.ID_ADMIN, "admin@ecomers.co");
    private static final EmpleadoActual TRABAJADOR = new EmpleadoActual(Tokens.ID_EMPLEADO, "ana.bodega@ecomers.co");

    @Autowired MockMvc mvc;
    @MockitoBean CatalogoAdminService servicio;

    // ---------------- Lectura: cualquier empleado ----------------

    @Test
    void cualquierEmpleadoListaLasCategoriasPeroUnClienteNiSinToken() throws Exception {
        when(servicio.listarCategorias(TRABAJADOR)).thenReturn(List.of(
                new CategoriaResponse(10L, null, "Portatiles", true, "https://x/c.png"),
                new CategoriaResponse(11L, 10L, "Gamer", false, null)));

        mvc.perform(get(BASE).with(Tokens.trabajador()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idPadre").doesNotExist())
                .andExpect(jsonPath("$[1].idPadre").value(10))
                .andExpect(jsonPath("$[1].activo").value(false))
                .andExpect(jsonPath("$[0].imagenUrl").value("https://x/c.png"))
                .andExpect(jsonPath("$[1].imagenUrl").doesNotExist());
        mvc.perform(get(BASE).with(Tokens.cliente())).andExpect(status().isForbidden());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    // ---------------- Escritura: el permiso CATEGORIAS ----------------

    @Test
    void conElPermisoCategoriasOComoAdminSeCreaUnaCategoria() throws Exception {
        when(servicio.crearCategoria(any(), any())).thenReturn(new CategoriaCreadaResponse(20L));

        mvc.perform(post(BASE).with(Tokens.trabajador("CATEGORIAS")).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.idCategoria").value(20));
        mvc.perform(post(BASE).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isCreated());

        verify(servicio).crearCategoria(TRABAJADOR, new CategoriaRequest("Portatiles", 10L, null));
        verify(servicio).crearCategoria(ADMIN, new CategoriaRequest("Portatiles", 10L, null));
    }

    @Test
    void laImagenUrlLlegaAlServicioAlCrearYEditar() throws Exception {
        when(servicio.crearCategoria(any(), any())).thenReturn(new CategoriaCreadaResponse(21L));
        String cuerpo = "{\"nombre\":\"Audio\",\"imagenUrl\":\"https://x/c.png\"}";

        mvc.perform(post(BASE).with(Tokens.trabajador("CATEGORIAS")).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated());
        mvc.perform(put(BASE + "/21").with(Tokens.trabajador("CATEGORIAS")).contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isNoContent());

        verify(servicio).crearCategoria(TRABAJADOR, new CategoriaRequest("Audio", null, "https://x/c.png"));
        verify(servicio).actualizarCategoria(TRABAJADOR, 21L, new CategoriaRequest("Audio", null, "https://x/c.png"));
    }

    @Test
    void sinElPermisoCategoriasTodasLasEscriturasDan403() throws Exception {
        for (RequestPostProcessor token : new RequestPostProcessor[] {
                Tokens.trabajador(), Tokens.trabajador("PRODUCTOS"), Tokens.cliente()}) {
            mvc.perform(post(BASE).with(token).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
            mvc.perform(put(BASE + "/20").with(token).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isForbidden());
            mvc.perform(put(BASE + "/20/estado").with(token).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"activo\":false}")).andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(CUERPO)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void seActualizaYSeCambiaElEstadoDeUnaCategoria() throws Exception {
        mvc.perform(put(BASE + "/20").with(Tokens.trabajador("CATEGORIAS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Portatiles pro\"}")).andExpect(status().isNoContent());
        mvc.perform(put(BASE + "/20/estado").with(Tokens.trabajador("CATEGORIAS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":false}")).andExpect(status().isNoContent());

        verify(servicio).actualizarCategoria(TRABAJADOR, 20L, new CategoriaRequest("Portatiles pro", null, null));
        verify(servicio).cambiarEstadoCategoria(TRABAJADOR, 20L, false);
    }

    // ---------------- Validacion ----------------

    @Test
    void datosInvalidosDan400SinLlegarAlServicio() throws Exception {
        String largo = "x".repeat(101);
        for (String malo : new String[] {
                "{\"nombre\":\"\"}", "{\"nombre\":\"   \"}", "{}", "{\"nombre\":\"" + largo + "\"}",
                "{\"nombre\":\"A\",\"idPadre\":\"abc\"}", "{\"nombre\":\"A\",\"idPadre\":1.5}",
                "{\"nombre\":\"A\",\"imagenUrl\":\"https://" + "x".repeat(500) + "\"}"}) {
            mvc.perform(post(BASE).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(malo))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put(BASE + "/20/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BASE + "/abc/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activo\":true}")).andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    // ---------------- Errores de la base ----------------

    @Test
    void padreInvalidoYNombreRepetidoDan409() throws Exception {
        when(servicio.crearCategoria(any(), any()))
                .thenThrow(new NegocioException(CodigoError.CATEGORIA_PADRE_INVALIDO, "ciclo", 20331))
                .thenThrow(new NegocioException(CodigoError.CATEGORIA_REPETIDA, "repetida", 20332));

        mvc.perform(post(BASE).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CATEGORIA_PADRE_INVALIDO"));
        mvc.perform(post(BASE).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.codigo").value("CATEGORIA_REPETIDA"));
    }

    @Test
    void categoriaInexistenteDa404YElPermisoDeLaBaseDa403() throws Exception {
        doThrow(new NegocioException(CodigoError.CATEGORIA_NO_ENCONTRADA, "no existe", 20330))
                .when(servicio).cambiarEstadoCategoria(ADMIN, 99L, true);
        doThrow(new NegocioException(CodigoError.PERMISO_INSUFICIENTE, "sin permiso", 20320))
                .when(servicio).cambiarEstadoCategoria(ADMIN, 5L, true);

        mvc.perform(put(BASE + "/99/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\":true}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.codigo").value("CATEGORIA_NO_ENCONTRADA"));
        mvc.perform(put(BASE + "/5/estado").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activo\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("PERMISO_INSUFICIENTE"));
    }
}
