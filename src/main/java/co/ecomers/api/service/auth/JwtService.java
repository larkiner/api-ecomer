package co.ecomers.api.service.auth;

import co.ecomers.api.model.Credencial;
import co.ecomers.api.security.ResolutorUsuario;
import co.ecomers.api.security.RolClaimConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Emite los tokens JWT (HS256) que identifican al usuario autenticado. El sujeto es el correo; el rol viaja en el
 * claim {@code rol} y, para los clientes, su ID_CLIENTE en {@code id_cliente}. La validación la hace el
 * {@code JwtDecoder} de {@code JwtConfig}.
 */
@Service
public class JwtService {

    /** Token emitido y cuánto dura. */
    public record TokenEmitido(String valor, Duration vida) { }

    private final JwtEncoder encoder;
    private final Duration vida;
    private final String emisor;

    public JwtService(JwtEncoder encoder,
                      @Value("${app.jwt.expiration-ms}") long expirationMs,
                      @Value("${app.jwt.issuer}") String emisor) {
        this.encoder = encoder;
        this.vida = Duration.ofMillis(expirationMs);
        this.emisor = emisor;
    }

    /**
     * Emite el token de la cuenta. Un cliente lleva {@code id_cliente}; un empleado o admin lleva {@code id_empleado} y la
     * lista {@code permisos} (los clientes nunca llevan permisos, aunque se pasen).
     */
    public TokenEmitido emitir(Credencial cuenta, List<String> permisos) {
        Instant ahora = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer(emisor)
                .subject(cuenta.email())
                .id(UUID.randomUUID().toString())
                .issuedAt(ahora)
                .expiresAt(ahora.plus(vida))
                .claim(RolClaimConverter.CLAIM_ROL, cuenta.rol());
        if (cuenta.esCliente()) {
            claims.claim(ResolutorUsuario.CLAIM_ID_CLIENTE, cuenta.id());
        } else {
            claims.claim(ResolutorUsuario.CLAIM_ID_EMPLEADO, cuenta.id());
            claims.claim(RolClaimConverter.CLAIM_PERMISOS, List.copyOf(permisos));
        }
        var cabecera = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(cabecera, claims.build())).getTokenValue();
        return new TokenEmitido(token, vida);
    }
}
