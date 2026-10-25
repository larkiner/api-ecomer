package co.ecomers.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Limite por usuario y bloqueo de una cuenta tras varias contrasenas equivocadas. */
class EndurecimientoTest {

    @AfterEach
    void limpiarContexto() {
        SecurityContextHolder.clearContext();
    }

    private static void autenticarComo(String correo) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(correo).claim("rol", "CLIENTE").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), correo));
    }

    private static int estado(RateLimitingFilter filtro, String ip) throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/carrito");
        req.setRemoteAddr(ip);
        var res = new MockHttpServletResponse();
        FilterChain cadena = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
        filtro.doFilter(req, res, cadena);
        return res.getStatus();
    }

    @Test
    void elLimitePorUsuarioCuentaLaCuentaAunqueCambieDeIp() throws Exception {
        var filtro = RateLimitingFilter.porUsuario(2, System::nanoTime);
        autenticarComo("ana@correo.com");

        assertThat(estado(filtro, "10.0.0.1")).isEqualTo(200);
        assertThat(estado(filtro, "10.0.0.2")).isEqualTo(200);
        assertThat(estado(filtro, "10.0.0.3")).isEqualTo(429);

        autenticarComo("otro@correo.com");
        assertThat(estado(filtro, "10.0.0.3")).isEqualTo(200);
    }

    @Test
    void sinTokenElLimitePorUsuarioNoAplica() throws Exception {
        var filtro = RateLimitingFilter.porUsuario(1, System::nanoTime);
        for (int i = 0; i < 5; i++) {
            assertThat(estado(filtro, "10.0.0.9")).isEqualTo(200);
        }
    }

    @Test
    void cincoContrasenasEquivocadasBloqueanLaCuentaQuinceMinutos() {
        var reloj = new AtomicLong(0);
        var limite = new LimiteIntentosLogin(reloj::get);
        for (int i = 0; i < LimiteIntentosLogin.INTENTOS_MAXIMOS; i++) {
            limite.verificar("ana@correo.com");
            limite.registrarFallo("ana@correo.com");
        }

        assertThatThrownBy(() -> limite.verificar("ana@correo.com"))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.DEMASIADAS_SOLICITUDES);
        assertThatCode(() -> limite.verificar("otra@correo.com")).doesNotThrowAnyException();

        reloj.addAndGet(LimiteIntentosLogin.BLOQUEO.plus(Duration.ofSeconds(1)).toNanos());
        assertThatCode(() -> limite.verificar("ana@correo.com")).doesNotThrowAnyException();
    }

    @Test
    void unLoginCorrectoBorraLosFallos() {
        var limite = new LimiteIntentosLogin();
        for (int i = 0; i < LimiteIntentosLogin.INTENTOS_MAXIMOS - 1; i++) {
            limite.registrarFallo("ana@correo.com");
        }
        limite.limpiar("ana@correo.com");
        limite.registrarFallo("ana@correo.com");
        assertThatCode(() -> limite.verificar("ana@correo.com")).doesNotThrowAnyException();
    }
}
