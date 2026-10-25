package co.ecomers.api.controller.webhook;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.ecomers.api.controller.PruebaWeb;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.service.integracion.NotificacionPagoService;
import co.ecomers.api.service.integracion.ResultadoWebhook;
import co.ecomers.api.service.integracion.ResultadoWebhook.Tipo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@PruebaWeb(WompiWebhookController.class)
class WebhookControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean NotificacionPagoService servicio;

    private ResultActions enviar(String cuerpo) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/wompi").contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    @Test
    void registradaEsPublicaYResponde200() throws Exception {
        when(servicio.procesar("{}")).thenReturn(new ResultadoWebhook(Tipo.REGISTRADA, 77L));
        enviar("{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.idPagoEvento").value(77))
                .andExpect(jsonPath("$.ignorado").value(false));
    }

    @Test
    void ignoradaResponde200SinId() throws Exception {
        when(servicio.procesar("{}")).thenReturn(new ResultadoWebhook(Tipo.IGNORADA, null));
        enviar("{}").andExpect(status().isOk()).andExpect(content().json("{\"ignorado\":true}", true));
    }

    @Test
    void cadaResultadoTieneSuEstado() throws Exception {
        when(servicio.procesar("a")).thenReturn(new ResultadoWebhook(Tipo.JSON_INVALIDO, null));
        when(servicio.procesar("b")).thenReturn(new ResultadoWebhook(Tipo.FIRMA_INVALIDA, null));
        when(servicio.procesar("c")).thenReturn(new ResultadoWebhook(Tipo.NO_CONFIGURADO, null));
        enviar("a").andExpect(status().isBadRequest());
        enviar("b").andExpect(status().isUnauthorized());
        enviar("c").andExpect(status().isServiceUnavailable());
    }

    @Test
    void baseOcupadaResponde503ParaQueWompiReintente() throws Exception {
        when(servicio.procesar(anyString())).thenThrow(new NegocioException(CodigoError.RECURSO_OCUPADO, "ocupado", 20188));
        enviar("{}").andExpect(status().isServiceUnavailable());
    }

    @Test
    void cuerpoDemasiadoGrandeSeRechazaSinProcesar() throws Exception {
        enviar("x".repeat(70_000)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.codigo").value("CUERPO_DEMASIADO_GRANDE"));
        verifyNoInteractions(servicio);
    }

    @Test
    void tipoDeContenidoDistintoDeJsonDa415() throws Exception {
        mvc.perform(post("/api/v1/webhooks/wompi").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(servicio);
    }

    @Test
    void soloSeAceptaPost() throws Exception {
        mvc.perform(get("/api/v1/webhooks/wompi")).andExpect(status().is4xxClientError());
        verifyNoInteractions(servicio);
    }
}
