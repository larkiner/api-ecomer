package co.ecomers.api.config;

import co.ecomers.api.security.UsuarioActualArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Permite recibir {@code ClienteActual} o {@code EmpleadoActual} como parametro en los controladores. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new UsuarioActualArgumentResolver());
    }
}
