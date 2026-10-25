package co.ecomers.api.model;

/**
 * Empleado autenticado (rol EMPLEADO o ADMIN). {@code idEmpleado} sale del claim {@code id_empleado} del token que firma
 * esta API; {@code usuario} es su correo, el sujeto del token.
 */
public record EmpleadoActual(long idEmpleado, String usuario) {

    /** Valor para DBMS_SESSION.SET_IDENTIFIER: el kardex y el historial de precios muestran quién hizo el cambio. */
    public String usuarioBd() {
        return "emp:" + usuario;
    }
}
