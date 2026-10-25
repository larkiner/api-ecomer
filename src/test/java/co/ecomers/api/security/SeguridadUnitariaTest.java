package co.ecomers.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SeguridadUnitariaTest {

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("ana@correo.com");
    }

    // ---------------- Rol del token ----------------

    @Test
    void elClaimRolSeConvierteEnAuthority() {
        var auth = new RolClaimConverter().convert(token().claim("rol", "cliente").build());

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CLIENTE");
        assertThat(auth.getName()).isEqualTo("ana@correo.com");
    }

    @Test
    void sinRolNoHayAuthorities() {
        assertThat(new RolClaimConverter().convert(token().claim("otro", 1).build()).getAuthorities()).isEmpty();
        assertThat(new RolClaimConverter().convert(token().claim("rol", "  ").build()).getAuthorities()).isEmpty();
    }

    // ---------------- Cliente y empleado actuales ----------------

    @Test
    void clienteSaleDelClaimIdCliente() {
        var c = ResolutorUsuario.cliente(token().claim("id_cliente", 7).build());
        assertThat(c.idCliente()).isEqualTo(7L);
        assertThat(c.sujeto()).isEqualTo("ana@correo.com");
        assertThat(c.usuarioBd()).isEqualTo("cli:7");
        assertThat(ResolutorUsuario.cliente(token().claim("id_cliente", "42").build()).idCliente()).isEqualTo(42L);
    }

    @Test
    void claimIdClienteAusenteOInvalidoEsClienteNoIdentificado() {
        for (Object malo : new Object[] {"abc", 0, -5, "", "7.5", null}) {
            var b = token();
            if (malo != null) {
                b.claim("id_cliente", malo);
            } else {
                b.claim("otro", 1);
            }
            Jwt jwt = b.build();
            assertThatThrownBy(() -> ResolutorUsuario.cliente(jwt))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.CLIENTE_NO_IDENTIFICADO);
        }
    }

    @Test
    void empleadoSaleDelCorreoYDelClaimIdEmpleado() {
        var e = ResolutorUsuario.empleado(token().subject("ana.bodega@ecomers.co").claim("id_empleado", 3).build());
        assertThat(e.idEmpleado()).isEqualTo(3L);
        assertThat(e.usuario()).isEqualTo("ana.bodega@ecomers.co");
        assertThat(e.usuarioBd()).isEqualTo("emp:ana.bodega@ecomers.co");
        assertThat(ResolutorUsuario.empleado(token().claim("id_empleado", "42").build()).idEmpleado()).isEqualTo(42L);
    }

    @Test
    void claimIdEmpleadoAusenteOInvalidoEsEmpleadoNoIdentificado() {
        for (Object malo : new Object[] {"abc", 0, -5, "", "7.5", null}) {
            var b = token();
            if (malo != null) {
                b.claim("id_empleado", malo);
            } else {
                b.claim("otro", 1);
            }
            Jwt jwt = b.build();
            assertThatThrownBy(() -> ResolutorUsuario.empleado(jwt))
                    .isInstanceOf(NegocioException.class)
                    .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.EMPLEADO_NO_IDENTIFICADO);
        }
    }

    // ---------------- Permisos delegados ----------------

    @Test
    void unTrabajadorRecibeSuRolYUnaAuthorityPorPermiso() {
        var auth = new RolClaimConverter().convert(
                token().claim("rol", "EMPLEADO").claim("permisos", List.of("CATEGORIAS", "productos")).build());

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_EMPLEADO", "PERM_CATEGORIAS", "PERM_PRODUCTOS");
    }

    @Test
    void unAdminEsTambienEmpleadoYTraeSusPermisos() {
        var auth = new RolClaimConverter().convert(
                token().claim("rol", "ADMIN").claim("permisos", List.of("CATEGORIAS", "PRODUCTOS")).build());

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_EMPLEADO", "PERM_CATEGORIAS", "PERM_PRODUCTOS");
    }

    @Test
    void unClienteNoRecibePermisosAunqueElTokenLosTraiga() {
        var auth = new RolClaimConverter().convert(
                token().claim("rol", "CLIENTE").claim("permisos", List.of("PRODUCTOS")).build());

        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_CLIENTE");
    }

    @Test
    void unClaimPermisosQueNoEsUnaListaNoRompeNiDaAuthorities() {
        for (Object raro : new Object[] {"PRODUCTOS", 5, Map.of("a", "b")}) {
            var auth = new RolClaimConverter().convert(token().claim("rol", "EMPLEADO").claim("permisos", raro).build());
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_EMPLEADO");
        }
        var conBasura = new RolClaimConverter().convert(
                token().claim("rol", "EMPLEADO").claim("permisos", java.util.Arrays.asList("CATEGORIAS", null, " ", 7)).build());
        assertThat(conBasura.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_EMPLEADO", "PERM_CATEGORIAS");
    }

    // ---------------- Revocacion (logout y cambio de contrasena) ----------------

    private static final Instant AHORA = Instant.now();

    private static Jwt tokenEmitidoEn(Instant iat, String jti) {
        return token().jti(jti).issuedAt(iat).expiresAt(iat.plusSeconds(3600)).claim("rol", "CLIENTE").build();
    }

    @Test
    void unTokenSinRevocarEsValido() {
        var validador = new RevocacionTokenValidator(new TokenBlacklistService(), new InvalidacionSesionService(3_600_000));
        assertThat(validador.validate(tokenEmitidoEn(AHORA, "j1")).hasErrors()).isFalse();
    }

    @Test
    void despuesDelLogoutElTokenSeRechazaPeroLosDemasSiguen() {
        var blacklist = new TokenBlacklistService();
        var validador = new RevocacionTokenValidator(blacklist, new InvalidacionSesionService(3_600_000));

        blacklist.revocar("j1", AHORA.plusSeconds(3600));

        assertThat(validador.validate(tokenEmitidoEn(AHORA, "j1")).hasErrors()).isTrue();
        assertThat(validador.validate(tokenEmitidoEn(AHORA, "j2")).hasErrors()).isFalse();
    }

    @Test
    void laRevocacionSeOlvidaCuandoElTokenYaVencio() {
        var blacklist = new TokenBlacklistService();
        blacklist.revocar("j1", AHORA.minusSeconds(1));
        assertThat(blacklist.estaRevocado("j1")).isFalse();
    }

    @Test
    void cambiarLaPasswordInvalidaLosTokensAnterioresDeEseUsuario() {
        var invalidacion = new InvalidacionSesionService(3_600_000);
        var validador = new RevocacionTokenValidator(new TokenBlacklistService(), invalidacion);
        Jwt viejo = tokenEmitidoEn(AHORA.minusSeconds(60), "j1");
        Jwt nuevo = tokenEmitidoEn(AHORA.plusSeconds(5), "j2");

        invalidacion.invalidarSesionesDe("ANA@correo.com");

        assertThat(validador.validate(viejo).hasErrors()).isTrue();
        assertThat(validador.validate(nuevo).hasErrors()).isFalse();
        // Otro usuario no se ve afectado
        assertThat(validador.validate(token().subject("luis@correo.com").jti("j3").issuedAt(AHORA.minusSeconds(60))
                .expiresAt(AHORA.plusSeconds(60)).build()).hasErrors()).isFalse();
    }

    // ---------------- Rate limiting ----------------

    private static int estado(RateLimitingFilter filtro, String uri, String ip) throws Exception {
        var req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.setRemoteAddr(ip);
        var res = new MockHttpServletResponse();
        FilterChain cadena = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
        filtro.doFilter(req, res, cadena);
        return res.getStatus();
    }

    @Test
    void superadoElLimiteResponde429HastaQueTermineLaVentana() throws Exception {
        var reloj = new AtomicLong(0);
        var filtro = new RateLimitingFilter(3, reloj::get);

        for (int i = 0; i < 3; i++) {
            assertThat(estado(filtro, "/api/v1/catalogo/variantes", "10.0.0.1")).isEqualTo(200);
        }
        assertThat(estado(filtro, "/api/v1/catalogo/variantes", "10.0.0.1")).isEqualTo(429);
        assertThat(estado(filtro, "/api/v1/catalogo/variantes", "10.0.0.2")).isEqualTo(200);

        reloj.addAndGet(Duration.ofSeconds(61).toNanos());
        assertThat(estado(filtro, "/api/v1/catalogo/variantes", "10.0.0.1")).isEqualTo(200);
    }

    @Test
    void elWebhookYLoQueNoEsApiNoSeLimitan() throws Exception {
        var filtro = new RateLimitingFilter(1, System::nanoTime);
        for (int i = 0; i < 5; i++) {
            assertThat(estado(filtro, "/api/v1/webhooks/wompi", "10.0.0.9")).isEqualTo(200);
            assertThat(estado(filtro, "/actuator/health", "10.0.0.9")).isEqualTo(200);
        }
    }

    @Test
    void elLimiteDeAuthSoloAplicaARegistroYLoginYTieneSuPropioContador() throws Exception {
        var filtro = new RateLimitingFilter(2, "/api/v1/auth/");

        assertThat(estado(filtro, "/api/v1/auth/login", "10.0.0.5")).isEqualTo(200);
        assertThat(estado(filtro, "/api/v1/auth/registro", "10.0.0.5")).isEqualTo(200);
        assertThat(estado(filtro, "/api/v1/auth/login", "10.0.0.5")).isEqualTo(429);
        // El resto de la API no cuenta contra este limite
        for (int i = 0; i < 5; i++) {
            assertThat(estado(filtro, "/api/v1/catalogo/variantes", "10.0.0.5")).isEqualTo(200);
        }
    }

    @Test
    void respuesta429EsProblemDetailConRetryAfter() throws Exception {
        var filtro = new RateLimitingFilter(1, System::nanoTime);
        estado(filtro, "/api/v1/carrito", "10.0.0.3");
        var req = new MockHttpServletRequest("GET", "/api/v1/carrito");
        req.setRemoteAddr("10.0.0.3");
        var res = new MockHttpServletResponse();
        var llamadas = new AtomicInteger();
        filtro.doFilter(req, res, (rq, rs) -> llamadas.incrementAndGet());

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(llamadas).hasValue(0);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
        assertThat(res.getContentType()).startsWith("application/problem+json");
        assertThat(res.getContentAsString()).contains("\"codigo\":\"DEMASIADAS_SOLICITUDES\"");
    }

    // ---------------- Tamano del cuerpo ----------------

    @Test
    void cuerpoDemasiadoGrandeResponde413SinLeerlo() throws Exception {
        var filtro = new TamanoCuerpoFilter(1024);
        var req = new MockHttpServletRequest("POST", "/api/v1/webhooks/wompi");
        req.setContent(new byte[2048]);
        var res = new MockHttpServletResponse();
        var llamadas = new AtomicInteger();

        filtro.doFilter(req, res, (rq, rs) -> llamadas.incrementAndGet());

        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(llamadas).hasValue(0);
    }

    @Test
    void cuerpoDentroDelLimitePasa() throws Exception {
        var filtro = new TamanoCuerpoFilter(1024);
        var req = new MockHttpServletRequest("POST", "/api/v1/webhooks/wompi");
        req.setContent(new byte[100]);
        var res = new MockHttpServletResponse();
        var llamadas = new AtomicInteger();

        filtro.doFilter(req, res, (rq, rs) -> llamadas.incrementAndGet());

        assertThat(llamadas).hasValue(1);
    }
}
