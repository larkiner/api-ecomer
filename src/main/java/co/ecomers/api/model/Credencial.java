package co.ecomers.api.model;

/**
 * Cuenta autenticable (cliente, empleado o admin), tal como la devuelve la base: el hash de la contraseña se usa solo
 * para verificar el login. Es el equivalente de {@code Credenciales} de la API de cafetería.
 */
public record Credencial(long id, String email, String passwordHash, boolean activo, String rol) {

    public static final String ROL_CLIENTE = "CLIENTE";
    public static final String ROL_EMPLEADO = "EMPLEADO";
    public static final String ROL_ADMIN = "ADMIN";

    public boolean esCliente() {
        return ROL_CLIENTE.equals(rol);
    }

    /** Empleado o admin: las cuentas de la tabla EMPLEADO. */
    public boolean esEmpleado() {
        return ROL_EMPLEADO.equals(rol) || ROL_ADMIN.equals(rol);
    }

    /** Nunca imprime el hash: un log o una traza no deben llevarlo. */
    @Override
    public String toString() {
        return "Credencial[id=" + id + ", email=" + email + ", rol=" + rol + ", activo=" + activo + "]";
    }
}
