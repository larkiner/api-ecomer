package co.ecomers.api.security;

import co.ecomers.api.exception.CodigoError;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Respuestas 401, 403, 413 y 429 de la capa de seguridad con el mismo formato JSON que el resto de
 * errores de la API (application/problem+json con {@code codigo} y {@code mensaje}). Los textos son
 * fijos, nunca datos de la peticion, asi que se arman sin serializador.
 */
public final class RespuestasSeguridad {

    private RespuestasSeguridad() { }

    /** 401 sin token o con token invalido. Mantiene la cabecera WWW-Authenticate del estandar Bearer. */
    public static AuthenticationEntryPoint noAutenticado() {
        var bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, e) -> {
            bearer.commence(request, response, e);
            escribir(response, CodigoError.NO_AUTENTICADO, "Se necesita un token válido");
        };
    }

    /** 403 con token valido pero sin el rol necesario. */
    public static AccessDeniedHandler accesoDenegado() {
        var bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, e) -> {
            bearer.handle(request, response, e);
            escribir(response, CodigoError.ACCESO_DENEGADO, "No tienes permiso para esta operación");
        };
    }

    static void escribir(HttpServletResponse response, CodigoError codigo, String mensaje) throws IOException {
        int estado = codigo.estado().value();
        response.setStatus(estado);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + codigo.name() + "\",\"status\":" + estado
                + ",\"detail\":\"" + mensaje + "\",\"codigo\":\"" + codigo.name() + "\",\"mensaje\":\"" + mensaje + "\"}");
    }
}
