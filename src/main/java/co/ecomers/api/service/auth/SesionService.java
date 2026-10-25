package co.ecomers.api.service.auth;

import co.ecomers.api.dto.auth.AuthResponse;
import co.ecomers.api.dto.auth.LoginRequest;
import co.ecomers.api.model.Credencial;
import co.ecomers.api.repository.backoffice.EmpleadoAuthRepository;
import co.ecomers.api.security.TokenBlacklistService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.security.LimiteIntentosLogin;

/**
 * Ciclo de vida de la sesión: iniciarla (login) y cerrarla (logout). No distingue entre cliente y empleado: delega la
 * búsqueda de credenciales en {@link CredencialService}.
 */
@Service
public class SesionService {

    private final CredencialService credenciales;
    private final JwtService jwt;
    private final TokenBlacklistService blacklist;
    private final EmpleadoAuthRepository empleados;
    private final LimiteIntentosLogin intentos;

    public SesionService(CredencialService credenciales, JwtService jwt, TokenBlacklistService blacklist,
                         EmpleadoAuthRepository empleados, LimiteIntentosLogin intentos) {
        this.intentos = intentos;
        this.credenciales = credenciales;
        this.jwt = jwt;
        this.blacklist = blacklist;
        this.empleados = empleados;
    }

    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        intentos.verificar(email);
        Credencial cuenta;
        try {
            cuenta = credenciales.autenticar(email, request.password());
        } catch (NegocioException e) {
            if (e.codigo() == CodigoError.CREDENCIALES_INVALIDAS) {
                intentos.registrarFallo(email);
            }
            throw e;
        }
        intentos.limpiar(email);
        return iniciar(cuenta);
    }

    /** Emite el token de una cuenta ya verificada (login) o recién creada (registro). Un empleado lleva sus permisos. */
    AuthResponse iniciar(Credencial cuenta) {
        List<String> permisos = cuenta.esCliente() ? List.of() : empleados.permisosDe(cuenta.id());
        var token = jwt.emitir(cuenta, permisos);
        return AuthResponse.bearer(token.valor(), token.vida().toSeconds(), cuenta.email(), cuenta.rol());
    }

    /**
     * Revoca el token actual ({@code jti}) hasta su expiración natural. Las sesiones son STATELESS: no hay nada que
     * borrar del servidor, solo se marca ese JWT como inválido para que el validador lo rechace.
     */
    public void cerrarSesion(String jti, Instant expira) {
        if (jti != null && expira != null) {
            blacklist.revocar(jti, expira);
        }
    }
}
