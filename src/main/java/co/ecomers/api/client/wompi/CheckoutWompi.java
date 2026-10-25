package co.ecomers.api.client.wompi;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Arma el enlace firmado al Web Checkout de Wompi. */
@Component
public class CheckoutWompi {

    public static final String MONEDA = "COP";

    private final WompiProperties props;

    public CheckoutWompi(WompiProperties props) {
        this.props = props;
    }

    public boolean configurado() {
        return props.checkoutConfigurado();
    }

    public Checkout crear(String referencia, long centavos, long idPedido) {
        String firma = FirmaIntegridad.calcular(referencia, centavos, MONEDA, props.integritySecret());
        String redirect = props.redirectPara(idPedido);
        return new Checkout(props.publicKey(), firma, redirect, armarUrl(props, referencia, centavos, firma, redirect));
    }

    static String armarUrl(WompiProperties p, String referencia, long centavos, String firma, String redirectUrl) {
        return p.checkoutUrl()
                + "?public-key=" + enc(p.publicKey())
                + "&currency=" + MONEDA
                + "&amount-in-cents=" + centavos
                + "&reference=" + enc(referencia)
                + "&signature%3Aintegrity=" + enc(firma)
                + "&redirect-url=" + enc(redirectUrl);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    public record Checkout(String publicKey, String firmaIntegridad, String redirectUrl, String url) { }
}
