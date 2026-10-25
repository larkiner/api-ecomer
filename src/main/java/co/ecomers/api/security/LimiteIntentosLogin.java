package co.ecomers.api.security;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Límite por cuenta en el login: tras {@value #INTENTOS_MAXIMOS} contraseñas equivocadas para el mismo correo, ese correo
 * queda bloqueado {@code BLOQUEO} (15 minutos desde el primer fallo). Complementa el límite por IP: frena a quien prueba
 * contraseñas contra una sola cuenta desde muchas IP. Cuenta igual los correos que no existen, para no revelar cuáles sí.
 *
 * <p>En memoria, como el resto de los límites: con varias instancias, cada una lleva su propia cuenta.
 */
@Component
public class LimiteIntentosLogin {

    static final int INTENTOS_MAXIMOS = 5;
    static final Duration BLOQUEO = Duration.ofMinutes(15);
    private static final int MAX_CUENTAS = 100_000;

    private final Cache<String, AtomicInteger> fallos;

    public LimiteIntentosLogin() {
        this(Ticker.systemTicker());
    }

    LimiteIntentosLogin(Ticker reloj) {
        this.fallos = Caffeine.newBuilder().expireAfterWrite(BLOQUEO).maximumSize(MAX_CUENTAS).ticker(reloj).build();
    }

    /** Lanza 429 si el correo agotó sus intentos. */
    public void verificar(String email) {
        AtomicInteger n = fallos.getIfPresent(email);
        if (n != null && n.get() >= INTENTOS_MAXIMOS) {
            throw new NegocioException(CodigoError.DEMASIADAS_SOLICITUDES,
                    "Hubo demasiados intentos fallidos con esta cuenta. Espera 15 minutos e inténtalo de nuevo");
        }
    }

    public void registrarFallo(String email) {
        fallos.get(email, k -> new AtomicInteger()).incrementAndGet();
    }

    /** Un login correcto borra los fallos acumulados. */
    public void limpiar(String email) {
        fallos.invalidate(email);
    }
}
