package co.ecomers.api.client.wompi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Firma de integridad de Wompi: SHA-256 de {@code referencia + centavos + moneda + secreto}, en hexadecimal. */
public final class FirmaIntegridad {

    private FirmaIntegridad() { }

    public static String calcular(String referencia, long centavos, String moneda, String secreto) {
        return sha256Hex(referencia + centavos + moneda + secreto);
    }

    static String sha256Hex(String texto) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
