package co.ecomers.api.controller.backoffice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.controller.Tokens;
import co.ecomers.api.dto.backoffice.SolicitudSubidaRequest;
import co.ecomers.api.dto.backoffice.SubidaImagenResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.service.backoffice.DestinoImagen;
import co.ecomers.api.service.backoffice.ImagenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@PruebaWeb(ImagenController.class)
class ImagenControllerTest {

    private static final String RUTA = "/api/v1/backoffice/imagenes/subida";
    private static final String RUTA_CATEGORIAS = "/api/v1/backoffice/imagenes/categorias/subida";
    private static final String CUERPO = "{\"tipoContenido\":\"image/png\"}";

    @Autowired MockMvc mvc;
    @MockitoBean ImagenService servicio;

    private static SubidaImagenResponse respuesta() {
        return new SubidaImagenResponse("https://abc.supabase.co/storage/v1/object/upload/sign/productos/productos/x.png?token=tok",
                "tok", "productos/x.png", "https://abc.supabase.co/storage/v1/object/public/productos/productos/x.png",
                "image/png", 7200);
    }

    @Test
    void conElPermisoProductosOComoAdminSeRecibeLaUrlDeSubidaSinSubirNadaPorLaApi() throws Exception {
        when(servicio.solicitarSubida(any(), any())).thenReturn(respuesta());

        for (RequestPostProcessor token : new RequestPostProcessor[] {Tokens.trabajador("PRODUCTOS"), Tokens.admin()}) {
            mvc.perform(post(RUTA).with(token).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.urlSubida").value(org.hamcrest.Matchers.containsString("?token=tok")))
                    .andExpect(jsonPath("$.token").value("tok"))
                    .andExpect(jsonPath("$.ruta").value("productos/x.png"))
                    .andExpect(jsonPath("$.urlPublica").value(org.hamcrest.Matchers.endsWith("/productos/productos/x.png")))
                    .andExpect(jsonPath("$.tipoContenido").value("image/png"))
                    .andExpect(jsonPath("$.expiraEnSegundos").value(7200));
        }
        verify(servicio, org.mockito.Mockito.times(2)).solicitarSubida(new SolicitudSubidaRequest("image/png"), DestinoImagen.PRODUCTOS);
    }

    @Test
    void sinElPermisoProductosNadieRecibeUnaUrlDeSubida() throws Exception {
        for (RequestPostProcessor token : new RequestPostProcessor[] {
                Tokens.trabajador(), Tokens.trabajador("CATEGORIAS"), Tokens.cliente()}) {
            mvc.perform(post(RUTA).with(token).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        }
        mvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(CUERPO)).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void laSubidaDeCategoriaVaASuCarpetaYSoloLaPideQuienTieneCategorias() throws Exception {
        when(servicio.solicitarSubida(any(), any())).thenReturn(respuesta());

        for (RequestPostProcessor token : new RequestPostProcessor[] {Tokens.trabajador("CATEGORIAS"), Tokens.admin()}) {
            mvc.perform(post(RUTA_CATEGORIAS).with(token).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.token").value("tok"));
        }
        verify(servicio, org.mockito.Mockito.times(2)).solicitarSubida(new SolicitudSubidaRequest("image/png"), DestinoImagen.CATEGORIAS);

        for (RequestPostProcessor token : new RequestPostProcessor[] {
                Tokens.trabajador(), Tokens.trabajador("PRODUCTOS"), Tokens.cliente()}) {
            mvc.perform(post(RUTA_CATEGORIAS).with(token).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.codigo").value("ACCESO_DENEGADO"));
        }
        mvc.perform(post(RUTA_CATEGORIAS).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isUnauthorized());
        verifyNoMoreInteractions(servicio);
    }

    @Test
    void sinTipoDeContenidoDa400() throws Exception {
        for (String malo : new String[] {"{}", "{\"tipoContenido\":\"\"}", "{\"tipoContenido\":null}"}) {
            mvc.perform(post(RUTA).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(malo))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("VALIDACION"));
        }
        verifyNoInteractions(servicio);
    }

    @Test
    void losErroresDelAlmacenSeTraducen() throws Exception {
        when(servicio.solicitarSubida(any(), any()))
                .thenThrow(new NegocioException(CodigoError.TIPO_IMAGEN_INVALIDO, "Solo jpeg, png o webp"))
                .thenThrow(new NegocioException(CodigoError.ALMACEN_IMAGENES_NO_CONFIGURADO, "Falta configurar Supabase"))
                .thenThrow(new NegocioException(CodigoError.ALMACEN_IMAGENES_NO_DISPONIBLE, "Intenta de nuevo"));

        mvc.perform(post(RUTA).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.codigo").value("TIPO_IMAGEN_INVALIDO"));
        mvc.perform(post(RUTA).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("ALMACEN_IMAGENES_NO_CONFIGURADO"));
        mvc.perform(post(RUTA).with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.codigo").value("ALMACEN_IMAGENES_NO_DISPONIBLE"));
    }

    @Test
    void laRutaSoloAceptaPostYNoHayOtrasRutasDeImagenes() throws Exception {
        mvc.perform(get(RUTA).with(Tokens.admin())).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/backoffice/imagenes/otra").with(Tokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO)).andExpect(status().isNotFound());
    }
}
