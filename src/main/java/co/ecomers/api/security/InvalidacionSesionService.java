package co.ecomers.api.security;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Invalida en bloque todos los tokens de un usuario emitidos antes de un instante (por ejemplo, al cambiar la
 * contraseña). Complementa a {@link TokenBlacklistService}, que revoca un token concreto. En memoria, con la misma
 * limitación de instancia única.
 */
@Component
public class InvalidacionSesionService {

    private final Map<String, Instant> invalidadosDesde = new ConcurrentHashMap<>();
    private final Duration vidaToken;

    public InvalidacionSesionService(@Value("${app.jwt.expiration-ms}") long expirationMs) {
        this.vidaToken = Duration.ofMillis(expirationMs);
    }

    /** Marca inválido todo token del usuario emitido hasta este momento. */
    public void invalidarSesionesDe(String email) {
        limpiarObsoletos();
        // El 'iat' del JWT tiene resolución de segundos: el corte se trunca al segundo para no rechazar un token
        // emitido justo después, en ese mismo segundo.
        invalidadosDesde.put(normalizar(email), Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** ¿El token (por su 'issued at') es anterior a la última invalidación? */
    public boolean tokenInvalidado(String email, Instant emitidoEn) {
        if (emitidoEn == null) {
            return false;
        }
        Instant desde = invalidadosDesde.get(normalizar(email));
        return desde != null && emitidoEn.isBefore(desde);
    }

    /** Una entrada más vieja que la vida de un token ya no invalida a nadie. */
    private void limpiarObsoletos() {
        Instant limite = Instant.now().minus(vidaToken);
        invalidadosDesde.values().removeIf(instante -> instante.isBefore(limite));
    }

    private static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
