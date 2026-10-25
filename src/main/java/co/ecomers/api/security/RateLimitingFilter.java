package co.ecomers.api.security;

import co.ecomers.api.exception.CodigoError;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limiting por IP (ventana fija de 1 minuto) para /api/**. La pasarela (webhook) queda fuera:
 * sus reintentos no deben perderse. En memoria y acotado (Caffeine expira las ventanas solo): para
 * varias instancias, cambiar por un contador compartido o limitar en el gateway.
 *
 * <p>Usa la IP remota; detras de un proxy hay que configurar server.forward-headers-strategy.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Duration VENTANA = Duration.ofMinutes(1);
    private static final int MAX_IPS = 100_000;

    private final int limite;
    /** Si no es nulo, el filtro limita SOLO las rutas con este prefijo (por ejemplo, registro y login). */
    private final String prefijo;
    private final Cache<String, AtomicInteger> ventanas;

    public RateLimitingFilter(int solicitudesPorMinuto) {
        this(solicitudesPorMinuto, null, Ticker.systemTicker());
    }

    /** Limite propio para las rutas bajo {@code prefijo}: tiene su propio contador, aparte del general. */
    public RateLimitingFilter(int solicitudesPorMinuto, String prefijo) {
        this(solicitudesPorMinuto, prefijo, Ticker.systemTicker());
    }

    RateLimitingFilter(int solicitudesPorMinuto, Ticker reloj) {
        this(solicitudesPorMinuto, null, reloj);
    }

    RateLimitingFilter(int solicitudesPorMinuto, String prefijo, Ticker reloj) {
        this.limite = solicitudesPorMinuto;
        this.prefijo = prefijo;
        this.ventanas = Caffeine.newBuilder().expireAfterWrite(VENTANA).maximumSize(MAX_IPS).ticker(reloj).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (limite <= 0) {
            return true;
        }
        if (prefijo != null) {
            return !uri.startsWith(prefijo);
        }
        return !uri.startsWith("/api/") || uri.startsWith("/api/v1/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AtomicInteger conteo = ventanas.get(request.getRemoteAddr(), ip -> new AtomicInteger());
        if (conteo.incrementAndGet() > limite) {
            response.setHeader("Retry-After", String.valueOf(VENTANA.toSeconds()));
            RespuestasSeguridad.escribir(response, CodigoError.DEMASIADAS_SOLICITUDES,
                    "Demasiadas solicitudes. Espera un momento e inténtalo de nuevo");
            return;
        }
        chain.doFilter(request, response);
    }
}
