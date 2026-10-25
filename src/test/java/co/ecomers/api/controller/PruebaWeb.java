package co.ecomers.api.controller;

import co.ecomers.api.config.JacksonConfig;
import co.ecomers.api.config.JwtConfig;
import co.ecomers.api.config.SecurityConfig;
import co.ecomers.api.config.WebConfig;
import co.ecomers.api.exception.ApiExceptionHandler;
import co.ecomers.api.security.InvalidacionSesionService;
import co.ecomers.api.security.TokenBlacklistService;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.TestPropertySource;

/**
 * Prueba de la capa web: controladores reales con la configuracion de seguridad real (incluida la firma y la
 * revocacion de tokens) y los servicios simulados. No necesita Oracle (el token lo fabrica spring-security-test).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest
@Import({SecurityConfig.class, JwtConfig.class, TokenBlacklistService.class, InvalidacionSesionService.class,
        WebConfig.class, JacksonConfig.class, ApiExceptionHandler.class})
@TestPropertySource(properties = {
        "app.jwt.secret=secreto-de-pruebas-de-al-menos-32-caracteres",
        "app.jwt.expiration-ms=3600000",
        "app.jwt.issuer=ecomers-api",
        "app.cors.allowed-origins=http://localhost:5173",
        "app.rate-limit.solicitudes-por-minuto=0",
        "app.rate-limit.auth-por-minuto=0",
        "app.cache.http-max-age-seg=60"})
public @interface PruebaWeb {

    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
