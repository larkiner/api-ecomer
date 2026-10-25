package co.ecomers.api.exception;

/**
 * Error esperado de negocio o de validacion. Lo lanzan los servicios y el traductor de
 * errores de Oracle; {@link ApiExceptionHandler} lo convierte en la respuesta HTTP.
 */
public class NegocioException extends RuntimeException {

    private final CodigoError codigo;
    private final int codigoOracle;

    public NegocioException(CodigoError codigo, String mensaje) {
        this(codigo, mensaje, 0);
    }

    public NegocioException(CodigoError codigo, String mensaje, int codigoOracle) {
        super(mensaje);
        this.codigo = codigo;
        this.codigoOracle = codigoOracle;
    }

    public CodigoError codigo() {
        return codigo;
    }

    /** ORA-20xxx en positivo, o 0 si el error no viene de la base. */
    public int codigoOracle() {
        return codigoOracle;
    }
}
