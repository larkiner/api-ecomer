package co.ecomers.api.service.auth;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.Credencial;
import co.ecomers.api.repository.backoffice.EmpleadoAuthRepository;
import co.ecomers.api.repository.tienda.ClienteAuthRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Punto único de lectura y verificación de credenciales, sin importar si la cuenta es de un cliente o de un empleado.
 * Centralizar aquí la distinción evita repetirla en cada servicio de auth: un tercer tipo de cuenta solo tocaría esta
 * clase (como {@code CredencialService} en la API de cafetería).
 */
@Service
public class CredencialService {

    private final ClienteAuthRepository clientes;
    private final EmpleadoAuthRepository empleados;
    private final PasswordEncoder passwordEncoder;
    /** Hash de relleno: si el correo no existe se compara contra él, para que no se note por el tiempo de respuesta. */
    private final String hashFicticio;

    public CredencialService(ClienteAuthRepository clientes, EmpleadoAuthRepository empleados,
                             PasswordEncoder passwordEncoder) {
        this.clientes = clientes;
        this.empleados = empleados;
        this.passwordEncoder = passwordEncoder;
        this.hashFicticio = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Busca la cuenta por correo: primero entre clientes, luego entre empleados. */
    public Optional<Credencial> buscarPorEmail(String email) {
        Optional<Credencial> cliente = clientes.buscarPorEmail(email);
        return cliente.isPresent() ? cliente : empleados.buscarPorEmail(email);
    }

    /** Login: la contraseña debe coincidir y la cuenta estar activa. Devuelve la cuenta. */
    public Credencial autenticar(String email, String passwordPlano) {
        Optional<Credencial> cuenta = buscarPorEmail(email);
        if (cuenta.isEmpty()) {
            passwordEncoder.matches(passwordPlano, hashFicticio);
            throw credencialesInvalidas();
        }
        verificarPassword(passwordPlano, cuenta.get());
        if (!cuenta.get().activo()) {
            throw new NegocioException(CodigoError.CUENTA_INACTIVA, "La cuenta está inactiva");
        }
        return cuenta.get();
    }

    /** Solo comprueba que la contraseña coincida (por ejemplo, al cambiarla). */
    public void verificarPassword(String passwordPlano, Credencial cuenta) {
        if (!passwordEncoder.matches(passwordPlano, cuenta.passwordHash())) {
            throw credencialesInvalidas();
        }
    }

    /** Mensaje genérico: no revela si el correo existe. */
    public static NegocioException credencialesInvalidas() {
        return new NegocioException(CodigoError.CREDENCIALES_INVALIDAS, "Email o contraseña incorrectos");
    }
}
