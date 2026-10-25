package co.ecomers.api.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rechaza los tokens revocados por logout (por su {@code jti}) y los emitidos antes de un cambio de contraseña (por
 * su {@code iat}). Es la parte de {@code JwtAuthFilter} de la API de cafetería que consulta la revocación.
 */
public class RevocacionTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOCADO = new OAuth2Error("invalid_token", "Token revocado (logout)", null);
    private static final OAuth2Error INVALIDADO =
            new OAuth2Error("invalid_token", "Token invalidado (cambio de contraseña)", null);

    private final TokenBlacklistService blacklist;
    private final InvalidacionSesionService invalidacion;

    public RevocacionTokenValidator(TokenBlacklistService blacklist, InvalidacionSesionService invalidacion) {
        this.blacklist = blacklist;
        this.invalidacion = invalidacion;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getId() != null && blacklist.estaRevocado(jwt.getId())) {
            return OAuth2TokenValidatorResult.failure(REVOCADO);
        }
        if (invalidacion.tokenInvalidado(jwt.getSubject(), jwt.getIssuedAt())) {
            return OAuth2TokenValidatorResult.failure(INVALIDADO);
        }
        return OAuth2TokenValidatorResult.success();
    }
}
