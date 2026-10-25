package co.ecomers.api.repository.tienda;

import co.ecomers.api.model.Credencial;
import co.ecomers.api.model.NuevoCliente;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Cuentas de cliente: alta, lectura de la credencial para el login y cambio de contraseña. Todo va por
 * PKG_CLIENTE (rol cliente); ver base-de-datos/auth-contrato-base-de-datos.md. El login ocurre antes de saber quién es el
 * usuario, por eso su identificador de sesión es fijo.
 */
@Repository
public class ClienteAuthRepository {

    private static final String PKG = "ECOMERS.PKG_CLIENTE.";
    private static final String USUARIO_AUTH = "auth:cliente";

    private final BaseDatosCliente db;

    public ClienteAuthRepository(BaseDatosCliente db) {
        this.db = db;
    }

    /** Crea el cliente y devuelve su ID_CLIENTE. Email o documento repetidos: ORA-20300 / ORA-20301. */
    public long registrar(NuevoCliente c) {
        return db.enTransaccion(USUARIO_AUTH, s -> s.funcionNumero(PKG + "registrar",
                c.nombre(), c.email(), c.tipoDocumento(), c.numeroDocumento(), c.telefono(), c.passwordHash()));
    }

    public Optional<Credencial> buscarPorEmail(String email) {
        return db.lectura(USUARIO_AUTH, s -> s.funcionCursor(PKG + "credencial_por_email",
                        (rs, n) -> new Credencial(rs.getLong("ID_CLIENTE"), rs.getString("EMAIL"),
                                rs.getString("PASSWORD_HASH"), rs.getInt("ACTIVO") == 1, Credencial.ROL_CLIENTE),
                        email))
                .stream().findFirst();
    }

    public void cambiarPassword(long idCliente, String passwordHash) {
        db.enTransaccionSinResultado("cli:" + idCliente,
                s -> s.procedimiento(PKG + "cambiar_password", idCliente, passwordHash));
    }
}
