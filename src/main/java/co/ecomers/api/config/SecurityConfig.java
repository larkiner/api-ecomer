package co.ecomers.api.config;

import co.ecomers.api.security.RateLimitingFilter;
import co.ecomers.api.security.RespuestasSeguridad;
import co.ecomers.api.security.RolClaimConverter;
import co.ecomers.api.security.TamanoCuerpoFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Matriz de autorizacion, centralizada aqui por patron de URL (no hay @PreAuthorize en el codigo):
 *
 * <pre>
 *  PUBLICO ......... catalogo (GET), registro y login, health, documentacion y el webhook (lo protege su firma)
 *  autenticado ..... logout y cambio de contrasena (cualquier rol)
 *  CLIENTE ......... carrito, pedidos y pagos propios        (claim id_cliente en el token)
 *  EMPLEADO ........ backoffice: inventario y reportes
 *  resto ........... denegado (todo endpoint nuevo debe declararse aqui)
 * </pre>
 *
 * El rol viaja en el claim {@code rol} del token que emite esta API (ver {@code JwtConfig}).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String[] DOCUMENTACION = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};
    static final int CUERPO_MAXIMO_BYTES = 64 * 1024;
    static final String RUTA_AUTH = "/api/v1/auth/";

    /** Swagger UI necesita HTML y JS: va en su propia cadena, sin la CSP estricta de la API. */
    @Bean
    @Order(1)
    public SecurityFilterChain documentacion(HttpSecurity http) throws Exception {
        http.securityMatcher(DOCUMENTACION)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain api(HttpSecurity http,
                                   @Value("${app.rate-limit.solicitudes-por-minuto:120}") int solicitudesPorMinuto,
                                   @Value("${app.rate-limit.auth-por-minuto:10}") int authPorMinuto)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // La API solo devuelve JSON: una CSP "default-src 'none'" es segura y limita un XSS.
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)))
                .authorizeHttpRequests(a -> a
                        // /error debe ser publico: si no, un 400 sin token se enmascara como 401 vacio.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/wompi").permitAll()
                        .requestMatchers(HttpMethod.POST, RUTA_AUTH + "registro", RUTA_AUTH + "login").permitAll()
                        .requestMatchers(HttpMethod.POST, RUTA_AUTH + "logout").authenticated()
                        .requestMatchers(HttpMethod.PUT, RUTA_AUTH + "password").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/catalogo/**").permitAll()
                        .requestMatchers("/api/v1/carrito/**", "/api/v1/pedidos/**").hasRole("CLIENTE")
                        // Backoffice: las reglas especificas van ANTES de la general (se aplica la primera que coincide).
                        .requestMatchers("/api/v1/backoffice/trabajadores/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/backoffice/categorias/**").hasAuthority("PERM_CATEGORIAS")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/backoffice/categorias/**").hasAuthority("PERM_CATEGORIAS")
                        .requestMatchers(HttpMethod.POST, "/api/v1/backoffice/productos/**", "/api/v1/backoffice/variantes/**")
                                .hasAuthority("PERM_PRODUCTOS")
                        .requestMatchers(HttpMethod.POST, "/api/v1/backoffice/imagenes/categorias/**").hasAuthority("PERM_CATEGORIAS")
                        .requestMatchers(HttpMethod.POST, "/api/v1/backoffice/imagenes/**").hasAuthority("PERM_PRODUCTOS")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/backoffice/productos/**", "/api/v1/backoffice/variantes/**")
                                .hasAuthority("PERM_PRODUCTOS")
                        .requestMatchers("/api/v1/backoffice/**").hasRole("EMPLEADO")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(resolutorDeToken())
                        .jwt(j -> j.jwtAuthenticationConverter(new RolClaimConverter()))
                        .authenticationEntryPoint(RespuestasSeguridad.noAutenticado())
                        .accessDeniedHandler(RespuestasSeguridad.accesoDenegado()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(RespuestasSeguridad.noAutenticado())
                        .accessDeniedHandler(RespuestasSeguridad.accesoDenegado()))
                .addFilterBefore(new TamanoCuerpoFilter(CUERPO_MAXIMO_BYTES), BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new RateLimitingFilter(solicitudesPorMinuto), TamanoCuerpoFilter.class)
                // Registro y login: mucho mas estricto, contra la fuerza bruta de credenciales. Misma clase y mismo
                // ancla, asi que Spring los ordena juntos (los dos van antes de TamanoCuerpoFilter).
                .addFilterBefore(new RateLimitingFilter(authPorMinuto, RUTA_AUTH), TamanoCuerpoFilter.class);
        return http.build();
    }

    /**
     * En las rutas publicas se ignora la cabecera Authorization: un token vencido o revocado que el navegador siga
     * enviando no debe impedir iniciar sesion ni ver el catalogo (la peticion sigue como anonima, como en la API de
     * cafeteria). En las rutas protegidas, un token malo sigue dando 401.
     */
    static BearerTokenResolver resolutorDeToken() {
        var porDefecto = new DefaultBearerTokenResolver();
        return request -> esRutaPublica(request) ? null : porDefecto.resolve(request);
    }

    static boolean esRutaPublica(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String metodo = request.getMethod();
        return uri.equals("/error")
                || uri.startsWith("/actuator/")
                || (HttpMethod.GET.matches(metodo) && uri.startsWith("/api/v1/catalogo/"))
                || (HttpMethod.POST.matches(metodo) && (uri.equals("/api/v1/webhooks/wompi")
                        || uri.equals(RUTA_AUTH + "registro") || uri.equals(RUTA_AUTH + "login")));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") List<String> origenes) {
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(origenes);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setMaxAge(3600L);
        var fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/api/**", cfg);
        return fuente;
    }
}
