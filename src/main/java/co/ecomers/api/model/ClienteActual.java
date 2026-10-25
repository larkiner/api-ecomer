package co.ecomers.api.model;

/**
 * Cliente autenticado. Su ID_CLIENTE sale del claim {@code id_cliente} del token que firma esta API (se escribe
 * al iniciar sesión, leyéndolo de la base), nunca del cuerpo de la petición.
 */
public record ClienteActual(long idCliente, String sujeto) {

    /** Valor para DBMS_SESSION.SET_IDENTIFIER: queda en el historial de pedidos. Usa el ID, no el correo (dato personal). */
    public String usuarioBd() {
        return "cli:" + idCliente;
    }
}
