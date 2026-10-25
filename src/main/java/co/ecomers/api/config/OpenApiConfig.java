package co.ecomers.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentacion OpenAPI en /swagger-ui.html (desactivable con API_DOCS_HABILITADO=false). */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ecomersOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ECOMERS API")
                        .version("v1")
                        .description("Tienda de tecnología: catálogo, carrito, pedidos, pagos con Wompi y backoffice. "
                                + "Registro y login en /api/v1/auth; los endpoints protegidos usan el token que devuelven (Bearer)."))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
