package co.ecomers.api.security;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.EmpleadoActual;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Inyecta {@link ClienteActual} o {@link EmpleadoActual} en los metodos de los controladores. */
public class UsuarioActualArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        Class<?> tipo = parameter.getParameterType();
        return ClienteActual.class.equals(tipo) || EmpleadoActual.class.equals(tipo);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binder) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new NegocioException(CodigoError.NO_AUTENTICADO, "Se necesita iniciar sesión");
        }
        return ClienteActual.class.equals(parameter.getParameterType())
                ? ResolutorUsuario.cliente(jwt)
                : ResolutorUsuario.empleado(jwt);
    }
}
