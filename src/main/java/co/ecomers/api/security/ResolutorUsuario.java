package co.ecomers.api.security;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.EmpleadoActual;
import java.math.BigDecimal;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Obtiene el usuario de negocio del token. El ID_CLIENTE o el ID_EMPLEADO salen de los claims {@code id_cliente} e
 * {@code id_empleado}: los escribe esta API al iniciar sesión, leyéndolos de la base, y el token va firmado, así que
 * son de confianza.
 */
public final class ResolutorUsuario {

    public static final String CLAIM_ID_CLIENTE = "id_cliente";
    public static final String CLAIM_ID_EMPLEADO = "id_empleado";

    private ResolutorUsuario() { }

    public static ClienteActual cliente(Jwt jwt) {
        long id = idDelClaim(jwt, CLAIM_ID_CLIENTE);
        if (id <= 0) {
            throw new NegocioException(CodigoError.CLIENTE_NO_IDENTIFICADO,
                    "El usuario no está asociado a un cliente de la tienda");
        }
        return new ClienteActual(id, jwt.getSubject());
    }

    public static EmpleadoActual empleado(Jwt jwt) {
        long id = idDelClaim(jwt, CLAIM_ID_EMPLEADO);
        if (id <= 0) {
            throw new NegocioException(CodigoError.EMPLEADO_NO_IDENTIFICADO,
                    "El usuario no está asociado a un empleado");
        }
        return new EmpleadoActual(id, jwt.getSubject());
    }

    /** El ID del claim, o 0 si falta, no es un entero o el token no tiene sujeto. */
    private static long idDelClaim(Jwt jwt, String claim) {
        Object valor = jwt.getClaims().get(claim);
        if (valor == null || jwt.getSubject() == null) {
            return 0;
        }
        try {
            return new BigDecimal(String.valueOf(valor).trim()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return 0;
        }
    }
}
