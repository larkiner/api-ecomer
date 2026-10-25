package co.ecomers.api.controller.webhook;

import co.ecomers.api.dto.webhook.WebhookResponse;
import co.ecomers.api.service.integracion.NotificacionPagoService;
import co.ecomers.api.service.integracion.ResultadoWebhook;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook de Wompi (publico: lo protege la firma del evento). Responde rapido y sin esperar a
 * aplicar el pago. 200 para lo que no tiene arreglo (asi Wompi no reintenta sin fin); 5xx solo
 * cuando un reintento puede ayudar.
 */
@RestController
public class WompiWebhookController {

    private final NotificacionPagoService servicio;

    public WompiWebhookController(NotificacionPagoService servicio) {
        this.servicio = servicio;
    }

    @PostMapping(value = "/api/v1/webhooks/wompi", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WebhookResponse> recibir(@RequestBody String cuerpo) {
        ResultadoWebhook r = servicio.procesar(cuerpo);
        return switch (r.tipo()) {
            case REGISTRADA -> ResponseEntity.ok(WebhookResponse.registrado(r.idPagoEvento()));
            case IGNORADA -> ResponseEntity.ok(WebhookResponse.ignorar());
            case JSON_INVALIDO -> ResponseEntity.badRequest().build();
            case FIRMA_INVALIDA -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            case NO_CONFIGURADO -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }
}
