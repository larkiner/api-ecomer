package co.ecomers.api.client.wompi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de Wompi. Todas las claves son opcionales para arrancar: sin las de checkout el
 * pago responde 503, y sin el secreto de eventos el webhook rechaza todo (nunca se acepta un
 * evento sin verificar su firma).
 */
@ConfigurationProperties("app.wompi")
public record WompiProperties(
        String publicKey,
        String integritySecret,
        String eventsSecret,
        String checkoutUrl,
        String redirectUrl) {

    public WompiProperties {
        if (checkoutUrl == null || checkoutUrl.isBlank()) {
            checkoutUrl = "https://checkout.wompi.co/p/";
        }
        if (redirectUrl == null) {
            redirectUrl = "";
        }
    }

    public boolean checkoutConfigurado() {
        return tieneTexto(publicKey) && tieneTexto(integritySecret);
    }

    public boolean eventosConfigurados() {
        return tieneTexto(eventsSecret);
    }

    /** {@code redirectUrl} puede traer {@code {idPedido}}. */
    public String redirectPara(long idPedido) {
        return redirectUrl.replace("{idPedido}", Long.toString(idPedido));
    }

    private static boolean tieneTexto(String s) {
        return s != null && !s.isBlank();
    }

    /** Nunca imprimir los secretos (logs, actuator). */
    @Override
    public String toString() {
        return "WompiProperties[publicKey=" + publicKey + ", checkoutUrl=" + checkoutUrl + ", secretos=***]";
    }
}
