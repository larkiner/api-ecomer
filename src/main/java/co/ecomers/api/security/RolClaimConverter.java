package co.ecomers.api.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Convierte los claims del token en authorities: {@code ROLE_<ROL>} por el claim {@code rol} y {@code PERM_<CODIGO>} por
 * cada permiso delegado del claim {@code permisos} (solo empleados y admins). Un {@code ADMIN} es también
 * {@code ROLE_EMPLEADO}. El nombre del usuario autenticado es el sujeto: su correo.
 */
public class RolClaimConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String CLAIM_ROL = "rol";
    public static final String CLAIM_PERMISOS = "permisos";

    private static final String ROL_ADMIN = "ADMIN";
    private static final String ROL_EMPLEADO = "EMPLEADO";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), jwt.getSubject());
    }

    static List<GrantedAuthority> authorities(Jwt jwt) {
        String rol = jwt.getClaimAsString(CLAIM_ROL);
        if (rol == null || rol.isBlank()) {
            return List.of();
        }
        rol = rol.trim().toUpperCase(Locale.ROOT);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + rol));
        if (ROL_ADMIN.equals(rol)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + ROL_EMPLEADO));
        }
        if (ROL_ADMIN.equals(rol) || ROL_EMPLEADO.equals(rol)) {
            permisos(jwt).forEach(p -> authorities.add(new SimpleGrantedAuthority("PERM_" + p)));
        }
        return authorities;
    }

    /** Los códigos de permiso del claim; ignora lo que no sea una lista de textos no vacíos. */
    private static List<String> permisos(Jwt jwt) {
        if (!(jwt.getClaims().get(CLAIM_PERMISOS) instanceof Collection<?> permisos)) {
            return List.of();
        }
        return permisos.stream()
                .filter(String.class::isInstance)
                .map(p -> ((String) p).trim().toUpperCase(Locale.ROOT))
                .filter(p -> !p.isEmpty())
                .distinct()
                .toList();
    }
}
