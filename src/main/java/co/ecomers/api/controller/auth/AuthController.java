package co.ecomers.api.controller.auth;

import co.ecomers.api.dto.auth.AuthResponse;
import co.ecomers.api.dto.auth.CambioPasswordRequest;
import co.ecomers.api.dto.auth.LoginRequest;
import co.ecomers.api.dto.auth.RegistroRequest;
import co.ecomers.api.service.auth.PasswordService;
import co.ecomers.api.service.auth.RegistroService;
import co.ecomers.api.service.auth.SesionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro, login, logout y cambio de contraseña. Registro y login son públicos; logout y contraseña piden un token
 * válido (el de {@code SecurityConfig}). El registro crea siempre un cliente.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistroService registro;
    private final SesionService sesion;
    private final PasswordService password;

    public AuthController(RegistroService registro, SesionService sesion, PasswordService password) {
        this.registro = registro;
        this.sesion = sesion;
        this.password = password;
    }

    @PostMapping("/registro")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse registrar(@Valid @RequestBody RegistroRequest request) {
        return registro.registrar(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return sesion.login(request);
    }

    /** Revoca el token actual: deja de servir aunque no haya vencido. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt token) {
        sesion.cerrarSesion(token.getId(), token.getExpiresAt());
    }

    /** Cambia la contraseña del usuario autenticado y cierra todas sus sesiones. */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cambiarPassword(@AuthenticationPrincipal Jwt token, @Valid @RequestBody CambioPasswordRequest request) {
        password.cambiarPassword(token.getSubject(), request);
    }
}
