package co.ecomers.api.client.wompi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import tools.jackson.databind.JsonNode;

/**
 * Verifica el checksum de un evento de Wompi: SHA-256 de los valores de {@code signature.properties}
 * (rutas dentro de {@code data}), en ese orden, mas {@code timestamp} y el secreto de eventos.
 * Es el control de seguridad real del webhook: la base confia en lo que llega por este canal.
 */
public final class VerificadorFirmaWompi {

    private VerificadorFirmaWompi() { }

    public static boolean esValida(JsonNode evento, String secreto) {
        JsonNode firma = evento.path("signature");
        JsonNode propiedades = firma.path("properties");
        String checksum = firma.path("checksum").asString("");
        JsonNode timestamp = evento.path("timestamp");
        if (!propiedades.isArray() || propiedades.isEmpty() || checksum.isBlank()
                || timestamp.isMissingNode() || timestamp.isNull() || secreto == null || secreto.isBlank()) {
            return false;
        }
        StringBuilder concatenado = new StringBuilder();
        for (JsonNode p : propiedades) {
            JsonNode valor = evento.path("data");
            for (String parte : p.asString("").split("\\.")) {
                valor = valor.path(parte);
            }
            if (valor.isMissingNode() || valor.isNull() || valor.isContainer()) {
                return false;
            }
            concatenado.append(valor.asString());
        }
        concatenado.append(timestamp.asString()).append(secreto);
        String calculado = FirmaIntegridad.sha256Hex(concatenado.toString());
        return MessageDigest.isEqual(calculado.getBytes(StandardCharsets.UTF_8),
                checksum.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }
}
