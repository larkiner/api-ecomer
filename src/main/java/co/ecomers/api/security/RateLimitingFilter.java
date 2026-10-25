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
import java.util.function.Function;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Rate limiting (ventana fija de 1 minuto) para /api/**, por IP o por usuario autenticado ({@link #porUsuario}). La pasarela (webhook) queda fuera:
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
    /** De que se cuenta: la IP, o el usuario del token. Si devuelve null, la peticion no se limita aqui. */
    private final Function<HttpServletRequest, String> clave;

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
        this(solicitudesPorMinuto, prefijo, reloj, HttpServletRequest::getRemoteAddr);
    }

    RateLimitingFilter(int solicitudesPorMinuto, String prefijo, Ticker reloj, Function<HttpServletRequest, String> clave) {
        this.clave = clave;
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
        String quien = clave.apply(request);
        if (quien == null) {
            chain.doFilter(request, response);
            return;
        }
        AtomicInteger conteo = ventanas.get(quien, k -> new AtomicInteger());
        if (conteo.incrementAndGet() > limite) {
            response.setHeader("Retry-After", String.valueOf(VENTANA.toSeconds()));
            RespuestasSeguridad.escribir(response, CodigoError.DEMASIADAS_SOLICITUDES,
                    "Demasiadas solicitudes. Espera un momento e inténtalo de nuevo");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Limite por usuario autenticado, sin importar desde cuantas IP llegue: frena a quien reparte un token entre muchas
     * maquinas. Va despues de la autenticacion del token; las peticiones anonimas las cubre el limite por IP.
     */
    public static RateLimitingFilter porUsuario(int solicitudesPorMinuto) {
        return porUsuario(solicitudesPorMinuto, Ticker.systemTicker());
    }

    static RateLimitingFilter porUsuario(int solicitudesPorMinuto, Ticker reloj) {
        return new RateLimitingFilter(solicitudesPorMinuto, null, reloj, RateLimitingFilter::usuarioAutenticado);
    }

    static String usuarioAutenticado(HttpServletRequest request) {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        return autenticacion instanceof JwtAuthenticationToken token ? "usuario:" + token.getName() : null;
    }
}
