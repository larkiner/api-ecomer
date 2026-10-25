package co.ecomers.api.service.auth;

import co.ecomers.api.dto.auth.CambioPasswordRequest;
import co.ecomers.api.model.Credencial;
import co.ecomers.api.repository.backoffice.EmpleadoAuthRepository;
import co.ecomers.api.repository.tienda.ClienteAuthRepository;
import co.ecomers.api.security.InvalidacionSesionService;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Cambio de contraseña del usuario autenticado (cliente o empleado). Al cambiarla se invalidan TODAS sus sesiones
 * anteriores, la actual incluida: tiene que volver a iniciar sesión.
 */
@Service
public class PasswordService {

    private final CredencialService credenciales;
    private final ClienteAuthRepository clientes;
    private final EmpleadoAuthRepository empleados;
    private final InvalidacionSesionService invalidacion;
    private final PasswordEncoder passwordEncoder;

    public PasswordService(CredencialService credenciales, ClienteAuthRepository clientes,
                           EmpleadoAuthRepository empleados, InvalidacionSesionService invalidacion,
                           PasswordEncoder passwordEncoder) {
        this.credenciales = credenciales;
        this.clientes = clientes;
        this.empleados = empleados;
        this.invalidacion = invalidacion;
        this.passwordEncoder = passwordEncoder;
    }

    public void cambiarPassword(String email, CambioPasswordRequest request) {
        Credencial cuenta = credenciales.buscarPorEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(CredencialService::credencialesInvalidas);
        credenciales.verificarPassword(request.passwordActual(), cuenta);

        PoliticaPassword.validar(request.passwordNueva());
        String nuevoHash = passwordEncoder.encode(request.passwordNueva());

        if (cuenta.esCliente()) {
            clientes.cambiarPassword(cuenta.id(), nuevoHash);
        } else {
            empleados.cambiarPassword(cuenta.id(), nuevoHash);
        }
        invalidacion.invalidarSesionesDe(cuenta.email());
    }
}
