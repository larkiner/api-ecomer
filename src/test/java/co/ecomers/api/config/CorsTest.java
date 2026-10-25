package co.ecomers.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** CORS: solo origenes exactos y bien escritos; Retry-After visible; sin cookies. */
class CorsTest {

    @Test
    void aceptaOrigenesExactosYQuitaEspacios() {
        assertThat(SecurityConfig.origenesValidos(List.of(" http://localhost:5173 ", "https://tienda.ecomers.co")))
                .containsExactly("http://localhost:5173", "https://tienda.ecomers.co");
    }

    @Test
    void rechazaComodinesRutasBarraFinalYListaVacia() {
        for (String malo : List.of("*", "https://*.ecomers.co", "https://tienda.ecomers.co/", "tienda.ecomers.co",
                "https://tienda.ecomers.co/app", "ftp://tienda.ecomers.co")) {
            assertThatThrownBy(() -> SecurityConfig.origenesValidos(List.of(malo)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("CORS_ALLOWED_ORIGINS");
        }
        assertThatThrownBy(() -> SecurityConfig.origenesValidos(List.of(" ")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void laConfiguracionExponeRetryAfterYNoAdmiteCredenciales() {
        var fuente = new SecurityConfig().corsConfigurationSource(List.of("http://localhost:5173"));
        var cfg = fuente.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/catalogo/variantes"));
        assertThat(cfg).isNotNull();
        assertThat(cfg.getExposedHeaders()).containsExactly("Retry-After");
        assertThat(cfg.getAllowCredentials()).isFalse();
        assertThat(cfg.checkOrigin("https://malicioso.com")).isNull();
        assertThat(cfg.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    }
}
