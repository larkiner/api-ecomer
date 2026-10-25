package co.ecomers.api.security;

import co.ecomers.api.exception.CodigoError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rechaza (413) los cuerpos mas grandes que el limite antes de leerlos. La API solo recibe JSON
 * pequenio; el limite evita que un cuerpo enorme (por ejemplo en el webhook publico) se cargue en memoria.
 */
public class TamanoCuerpoFilter extends OncePerRequestFilter {

    private final long maximoBytes;

    public TamanoCuerpoFilter(long maximoBytes) {
        this.maximoBytes = maximoBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maximoBytes) {
            RespuestasSeguridad.escribir(response, CodigoError.CUERPO_DEMASIADO_GRANDE,
                    "El cuerpo de la petición es demasiado grande");
            return;
        }
        chain.doFilter(request, response);
    }
}
