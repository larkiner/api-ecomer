package co.ecomers.api.service.auth;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import java.util.Set;

/**
 * Política mínima de robustez de contraseñas, complementaria a la longitud (8 a 72) que ya validan los DTO. Rechaza los
 * patrones triviales que la longitud por sí sola no cubre: solo números, un único carácter repetido, secuencias obvias
 * y las más comunes de las filtraciones públicas. Se aplica en el registro y en el cambio de contraseña.
 */
public final class PoliticaPassword {

    /** Muestra de contraseñas muy comunes (en minúsculas). */
    private static final Set<String> COMUNES = Set.of(
            "password", "contrasena", "contraseña", "12345678", "123456789",
            "1234567890", "qwertyui", "qwerty123", "password1", "password123",
            "admin123", "iloveyou", "welcome1", "ecomers", "ecomers123",
            "changeme", "cambia-esto");

    private PoliticaPassword() { }

    /** Lanza PASSWORD_DEBIL (400) si la contraseña no cumple la política mínima. */
    public static void validar(String password) {
        String normal = password.toLowerCase();

        if (COMUNES.contains(normal)) {
            throw debil();
        }
        if (password.chars().allMatch(Character::isDigit)) {
            throw debil();
        }
        if (password.chars().distinct().count() <= 2) {
            throw debil(); // por ejemplo "aaaaaaaa" o "abababab"
        }
        if (esSecuencia(normal)) {
            throw debil(); // por ejemplo "abcdefgh" o "87654321"
        }
    }

    /** Detecta secuencias estrictamente ascendentes o descendentes de a 1. */
    private static boolean esSecuencia(String s) {
        boolean asc = true;
        boolean desc = true;
        for (int i = 1; i < s.length(); i++) {
            int diff = s.charAt(i) - s.charAt(i - 1);
            if (diff != 1) {
                asc = false;
            }
            if (diff != -1) {
                desc = false;
            }
        }
        return asc || desc;
    }

    private static NegocioException debil() {
        return new NegocioException(CodigoError.PASSWORD_DEBIL,
                "La contraseña es demasiado débil o común; elige una menos predecible");
    }
}
