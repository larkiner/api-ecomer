package co.ecomers.api.exception;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Manejo global de errores en formato RFC 9457 (application/problem+json) con las propiedades
 * {@code codigo} y {@code mensaje} (y {@code errores} en las validaciones). Nunca se expone la
 * traza ni el mensaje de un error interno de la base.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String MENSAJE_GENERICO = "Ocurrió un error inesperado";

    @ExceptionHandler(NegocioException.class)
    public ResponseEntity<ProblemDetail> negocio(NegocioException e) {
        if (e.codigo() == CodigoError.ERROR_INTERNO) {
            log.error("Error interno de la base (ORA-{}): {}", e.codigoOracle(), e.getMessage());
            return respuesta(CodigoError.ERROR_INTERNO, MENSAJE_GENERICO);
        }
        return respuesta(e.codigo(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validacion(MethodArgumentNotValidException e) {
        var respuesta = respuesta(CodigoError.VALIDACION, "La petición tiene datos inválidos");
        List<Map<String, String>> errores = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("campo", f.getField(), "mensaje", String.valueOf(f.getDefaultMessage())))
                .toList();
        respuesta.getBody().setProperty("errores", errores);
        return respuesta;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> cuerpoInvalido(Exception e) {
        return respuesta(CodigoError.VALIDACION, "La petición no se pudo interpretar");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> inesperado(Exception e) {
        // Las excepciones estandar de Spring MVC (404, 405, 415, parametro faltante...) ya traen su 4xx.
        if (e instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            int estado = er.getStatusCode().value();
            CodigoError codigo = estado == 404 ? CodigoError.NO_ENCONTRADO
                    : estado == 400 ? CodigoError.VALIDACION : CodigoError.SOLICITUD_INVALIDA;
            ProblemDetail pd = er.getBody();
            String mensaje = pd.getDetail() == null ? "Solicitud inválida" : pd.getDetail();
            pd.setTitle(codigo.name());
            pd.setProperty("codigo", codigo.name());
            pd.setProperty("mensaje", mensaje);
            return ResponseEntity.status(er.getStatusCode()).headers(er.getHeaders()).body(pd);
        }
        log.error("Error no controlado", e);
        return respuesta(CodigoError.ERROR_INTERNO, MENSAJE_GENERICO);
    }

    public static ProblemDetail problema(CodigoError codigo, String mensaje) {
        HttpStatus estado = codigo.estado();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(estado, mensaje);
        pd.setTitle(codigo.name());
        pd.setProperty("codigo", codigo.name());
        pd.setProperty("mensaje", mensaje);
        return pd;
    }

    private static ResponseEntity<ProblemDetail> respuesta(CodigoError codigo, String mensaje) {
        return ResponseEntity.status(codigo.estado()).body(problema(codigo, mensaje));
    }
}
