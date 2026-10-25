package co.ecomers.api.dto.auth;

/** Sesion iniciada: el token va en {@code Authorization: Bearer <token>} y vence en {@code expiraEnSegundos}. */
public record AuthResponse(
        String token,
        String tipo,
        long expiraEnSegundos,
        String email,
        String rol
) {

    public static AuthResponse bearer(String token, long expiraEnSegundos, String email, String rol) {
        return new AuthResponse(token, "Bearer", expiraEnSegundos, email, rol);
    }
}
