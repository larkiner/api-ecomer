package co.ecomers.api.dto.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Respuesta a la pasarela: el id del evento registrado, o que se ignoro (sin efecto). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookResponse(Long idPagoEvento, boolean ignorado) {

    public static WebhookResponse registrado(long idPagoEvento) {
        return new WebhookResponse(idPagoEvento, false);
    }

    public static WebhookResponse ignorar() {
        return new WebhookResponse(null, true);
    }
}
