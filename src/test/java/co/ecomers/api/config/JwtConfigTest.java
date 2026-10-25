package co.ecomers.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.ecomers.api.model.Credencial;
import co.ecomers.api.security.InvalidacionSesionService;
import co.ecomers.api.security.TokenBlacklistService;
import co.ecomers.api.service.auth.JwtService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;

/** Firma y validacion de los tokens propios, con el mismo codigo de produccion y sin Spring. */
class JwtConfigTest {

    private static final String SECRETO = "secreto-de-pruebas-de-al-menos-32-caracteres";
    private static final String EMISOR = "ecomers-api";

    private final JwtConfig config = new JwtConfig();
    private final TokenBlacklistService blacklist = new TokenBlacklistService();
    private final InvalidacionSesionService invalidacion = new InvalidacionSesionService(3_600_000);

    private JwtDecoder decoder(String secreto, String emisor) {
        return config.jwtDecoder(config.claveJwt(secreto), emisor, blacklist, invalidacion);
    }

    private JwtService servicio(String secreto, long vidaMs, String emisor) {
        return new JwtService(config.jwtEncoder(config.claveJwt(secreto)), vidaMs, emisor);
    }

    private static final Credencial CLIENTE = new Credencial(7L, "ana@correo.com", "hash", true, "CLIENTE");
    private static final Credencial EMPLEADO = new Credencial(3L, "bodega@ecomers.co", "hash", true, "EMPLEADO");

    @Test
    void elTokenDeUnClienteLlevaCorreoRolEIdCliente() {
        var emitido = servicio(SECRETO, 3_600_000, EMISOR).emitir(CLIENTE, List.of());

        Jwt jwt = decoder(SECRETO, EMISOR).decode(emitido.valor());

        assertThat(jwt.getSubject()).isEqualTo("ana@correo.com");
        assertThat(jwt.<String>getClaim("rol")).isEqualTo("CLIENTE");
        assertThat(((Number) jwt.getClaim("id_cliente")).longValue()).isEqualTo(7L);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
        assertThat(emitido.vida()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void elTokenDeUnEmpleadoLlevaSuIdYSusPermisosPeroNoIdCliente() {
        var jwt = decoder(SECRETO, EMISOR).decode(
                servicio(SECRETO, 3_600_000, EMISOR).emitir(EMPLEADO, List.of("CATEGORIAS", "PRODUCTOS")).valor());

        assertThat(jwt.<String>getClaim("rol")).isEqualTo("EMPLEADO");
        assertThat(((Number) jwt.getClaim("id_empleado")).longValue()).isEqualTo(3L);
        assertThat(jwt.<List<String>>getClaim("permisos")).containsExactly("CATEGORIAS", "PRODUCTOS");
        assertThat(jwt.getClaims()).doesNotContainKey("id_cliente");
    }

    @Test
    void elTokenDeUnAdminLlevaRolAdminYSusPermisos() {
        var admin = new Credencial(1L, "admin@ecomers.co", "hash", true, "ADMIN");
        var jwt = decoder(SECRETO, EMISOR).decode(
                servicio(SECRETO, 3_600_000, EMISOR).emitir(admin, List.of("CATEGORIAS", "PRODUCTOS")).valor());

        assertThat(jwt.<String>getClaim("rol")).isEqualTo("ADMIN");
        assertThat(((Number) jwt.getClaim("id_empleado")).longValue()).isEqualTo(1L);
        assertThat(jwt.<List<String>>getClaim("permisos")).containsExactly("CATEGORIAS", "PRODUCTOS");
    }

    @Test
    void elTokenDeUnClienteNoLlevaPermisosNiIdEmpleado() {
        var jwt = decoder(SECRETO, EMISOR).decode(servicio(SECRETO, 3_600_000, EMISOR).emitir(CLIENTE, List.of("PRODUCTOS")).valor());

        assertThat(jwt.getClaims()).doesNotContainKeys("permisos", "id_empleado");
    }

    @Test
    void cadaTokenTieneUnJtiDistinto() {
        var servicio = servicio(SECRETO, 3_600_000, EMISOR);
        var decoder = decoder(SECRETO, EMISOR);

        assertThat(decoder.decode(servicio.emitir(CLIENTE, List.of()).valor()).getId())
                .isNotEqualTo(decoder.decode(servicio.emitir(CLIENTE, List.of()).valor()).getId());
    }

    @Test
    void unTokenFirmadoConOtroSecretoSeRechaza() {
        String ajeno = servicio("otro-secreto-distinto-de-al-menos-32-caracteres", 3_600_000, EMISOR).emitir(CLIENTE, List.of()).valor();

        assertThatThrownBy(() -> decoder(SECRETO, EMISOR).decode(ajeno)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void unTokenDeOtroEmisorSeRechaza() {
        String deOtro = servicio(SECRETO, 3_600_000, "otra-api").emitir(CLIENTE, List.of()).valor();

        assertThatThrownBy(() -> decoder(SECRETO, EMISOR).decode(deOtro)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void unTokenVencidoSeRechaza() {
        // JwtService no emite tokens ya vencidos: se firma uno a mano, con la misma clave, emitido hace 10 min y vencido hace 5.
        var ahora = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(EMISOR).subject("ana@correo.com").id("j-vencido")
                .issuedAt(ahora.minus(Duration.ofMinutes(10))).expiresAt(ahora.minus(Duration.ofMinutes(5))).build();
        String vencido = config.jwtEncoder(config.claveJwt(SECRETO))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThatThrownBy(() -> decoder(SECRETO, EMISOR).decode(vencido)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void unTokenSinFirmaOMalFormadoSeRechaza() {
        var decoder = decoder(SECRETO, EMISOR);
        // {"alg":"none"}.{"sub":"ana@correo.com","iss":"ecomers-api"}.
        String sinFirma = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhbmFAY29ycmVvLmNvbSIsImlzcyI6ImVjb21lcnMtYXBpIn0.";

        assertThatThrownBy(() -> decoder.decode(sinFirma)).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> decoder.decode("abc")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void unTokenRevocadoPorLogoutSeRechaza() {
        var decoder = decoder(SECRETO, EMISOR);
        String valor = servicio(SECRETO, 3_600_000, EMISOR).emitir(CLIENTE, List.of()).valor();
        Jwt jwt = decoder.decode(valor);

        blacklist.revocar(jwt.getId(), jwt.getExpiresAt());

        assertThatThrownBy(() -> decoder.decode(valor)).isInstanceOf(BadJwtException.class)
                .hasMessageContaining("revocado");
    }

    @Test
    void elSecretoCortoHaceFallarElArranque() {
        assertThatThrownBy(() -> config.claveJwt("corto"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
