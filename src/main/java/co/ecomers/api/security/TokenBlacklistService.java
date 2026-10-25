package co.ecomers.api.security;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Lista de revocación de JWT (logout). Las sesiones son STATELESS: un JWT firmado sigue válido hasta que expira
 * aunque el usuario haga logout. Esta lista en memoria marca su {@code jti} como revocado mientras dure su vida útil.
 *
 * <p>En memoria a propósito (igual que en la API de cafetería): la API corre en una sola instancia. Con más de una,
 * o para que sobreviva a un reinicio, hay que moverla a un almacén compartido (Redis).
 */
@Component
public class TokenBlacklistService {

    private final Map<String, Instant> revocados = new ConcurrentHashMap<>();

    public void revocar(String jti, Instant expiracion) {
        limpiarExpirados();
        revocados.put(jti, expiracion);
    }

    public boolean estaRevocado(String jti) {
        Instant expiracion = revocados.get(jti);
        if (expiracion == null) {
            return false;
        }
        if (expiracion.isBefore(Instant.now())) {
            revocados.remove(jti);
            return false;
        }
        return true;
    }

    /** Evita que la lista crezca sin límite con tokens ya vencidos. */
    private void limpiarExpirados() {
        Instant ahora = Instant.now();
        revocados.values().removeIf(expiracion -> expiracion.isBefore(ahora));
    }
}
