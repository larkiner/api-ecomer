package co.ecomers.api.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tokens de prueba con la forma de los que emite la API: sujeto = correo, rol y, segun el caso, {@code id_cliente}
 * o {@code id_empleado} y {@code permisos}. Las authorities son las que arma {@code RolClaimConverter}.
 */
public final class Tokens {

    /** ID_EMPLEADO de los tokens de empleado y de trabajador. */
    public static final long ID_EMPLEADO = 3L;
    /** ID_EMPLEADO del token de admin. */
    public static final long ID_ADMIN = 1L;

    private Tokens() { }

    /** Cliente con ID_CLIENTE 7. */
    public static RequestPostProcessor cliente() {
        return jwt().jwt(j -> j.subject("ana@correo.com").jti("jti-cliente").claim("rol", "CLIENTE").claim("id_cliente", 7))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));
    }

    /** Rol CLIENTE pero sin el claim id_cliente (token que no trae el ID del cliente). */
    public static RequestPostProcessor clienteSinId() {
        return jwt().jwt(j -> j.subject("ana@correo.com").claim("rol", "CLIENTE"))
                .authorities(new SimpleGrantedAuthority("ROLE_CLIENTE"));
    }

    /** Trabajador sin permisos delegados (solo inventario y reportes). */
    public static RequestPostProcessor empleado() {
        return trabajador();
    }

    /** Trabajador (rol EMPLEADO) con los permisos delegados indicados, por ejemplo {@code "CATEGORIAS"}. */
    public static RequestPostProcessor trabajador(String... permisos) {
        List<GrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_EMPLEADO")));
        for (String p : permisos) {
            authorities.add(new SimpleGrantedAuthority("PERM_" + p));
        }
        return jwt().jwt(j -> j.subject("ana.bodega@ecomers.co").jti("jti-empleado").claim("rol", "EMPLEADO")
                        .claim("id_empleado", ID_EMPLEADO).claim("permisos", List.of(permisos)))
                .authorities(authorities);
    }

    /** Admin: ROLE_ADMIN, ROLE_EMPLEADO y todos los permisos. */
    public static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin@ecomers.co").jti("jti-admin").claim("rol", "ADMIN")
                        .claim("id_empleado", ID_ADMIN).claim("permisos", List.of("CATEGORIAS", "PRODUCTOS")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_EMPLEADO"),
                        new SimpleGrantedAuthority("PERM_CATEGORIAS"), new SimpleGrantedAuthority("PERM_PRODUCTOS"));
    }

    /** Empleado de antes de este cambio: tiene rol pero el token no trae id_empleado. */
    public static RequestPostProcessor empleadoSinId() {
        return jwt().jwt(j -> j.subject("ana.bodega@ecomers.co").claim("rol", "EMPLEADO"))
                .authorities(new SimpleGrantedAuthority("ROLE_EMPLEADO"));
    }
}
