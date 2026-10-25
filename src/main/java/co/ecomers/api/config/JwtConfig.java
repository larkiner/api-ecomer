package co.ecomers.api.config;

import co.ecomers.api.security.InvalidacionSesionService;
import co.ecomers.api.security.RevocacionTokenValidator;
import co.ecomers.api.security.TokenBlacklistService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Firma y validación de los tokens propios (HS256) y el hash de contraseñas (BCrypt). El secreto sale de
 * {@code JWT_SECRET} y la app no arranca sin él ni con uno corto (fail-closed).
 */
@Configuration
public class JwtConfig {

    /** HS256 exige al menos 256 bits. */
    static final int SECRETO_MINIMO_BYTES = 32;

    @Bean
    public SecretKey claveJwt(@Value("${app.jwt.secret}") String secreto) {
        byte[] bytes = secreto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < SECRETO_MINIMO_BYTES) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos " + SECRETO_MINIMO_BYTES
                    + " caracteres (HS256 pide 256 bits)");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey claveJwt) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(claveJwt));
    }

    /** Valida firma, vigencia y emisor, y además que el token no esté revocado (logout o cambio de contraseña). */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey claveJwt, @Value("${app.jwt.issuer}") String emisor,
                                 TokenBlacklistService blacklist, InvalidacionSesionService invalidacion) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(claveJwt).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(emisor),
                new RevocacionTokenValidator(blacklist, invalidacion)));
        return decoder;
    }

    /** BCrypt: el mismo algoritmo con el que se guardan los hashes en la base. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
