package co.ecomers.api.service.auth;

import co.ecomers.api.dto.auth.AuthResponse;
import co.ecomers.api.dto.auth.RegistroRequest;
import co.ecomers.api.model.Credencial;
import co.ecomers.api.model.NuevoCliente;
import co.ecomers.api.repository.tienda.ClienteAuthRepository;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registro público: crea SIEMPRE un cliente (el personal lo da de alta el administrador de la base). Correo y
 * documento repetidos los rechaza la base (ORA-20300 / ORA-20301 → 409): así no hay carrera entre "consultar y
 * guardar". Al terminar el cliente queda con la sesión iniciada.
 */
@Service
public class RegistroService {

    private final ClienteAuthRepository clientes;
    private final PasswordEncoder passwordEncoder;
    private final SesionService sesion;

    public RegistroService(ClienteAuthRepository clientes, PasswordEncoder passwordEncoder, SesionService sesion) {
        this.clientes = clientes;
        this.passwordEncoder = passwordEncoder;
        this.sesion = sesion;
    }

    public AuthResponse registrar(RegistroRequest request) {
        PoliticaPassword.validar(request.password());

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String hash = passwordEncoder.encode(request.password());

        long idCliente = clientes.registrar(new NuevoCliente(
                request.nombre().trim(),
                email,
                request.tipoDocumento().trim().toUpperCase(Locale.ROOT),
                request.numeroDocumento().trim(),
                request.telefono(),
                hash));

        return sesion.iniciar(new Credencial(idCliente, email, hash, true, Credencial.ROL_CLIENTE));
    }
}
