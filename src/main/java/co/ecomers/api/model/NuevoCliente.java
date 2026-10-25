package co.ecomers.api.model;

/** Datos para dar de alta un cliente. El hash BCrypt lo calcula el servicio: la base nunca ve la contraseña. */
public record NuevoCliente(String nombre, String email, String tipoDocumento, String numeroDocumento,
                           String telefono, String passwordHash) {

    @Override
    public String toString() {
        return "NuevoCliente[email=" + email + "]";
    }
}
