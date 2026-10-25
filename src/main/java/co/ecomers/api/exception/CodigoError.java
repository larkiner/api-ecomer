package co.ecomers.api.exception;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Codigos de error de la API. Los que vienen de la base mapean los ORA-20xxx de
 * ECOMERS_contexto_backend.md (seccion 13); se guardan en positivo, como los devuelve
 * {@code SQLException.getErrorCode()}. El mapeo es SIEMPRE por codigo, nunca por texto.
 */
public enum CodigoError {

    // Bugs o inconsistencias internas: se responde 500 generico y nunca se expone el mensaje de Oracle.
    ERROR_INTERNO(HttpStatus.INTERNAL_SERVER_ERROR,
            20010, 20011, 20100, 20124, 20126, 20127, 20169,
            20200, 20201, 20202, 20203, 20205, 20206, 20207, 20208, 20209),

    // Catalogo, carrito e inventario
    VARIANTE_NO_DISPONIBLE(HttpStatus.CONFLICT, 20101),
    CANTIDAD_INVALIDA(HttpStatus.BAD_REQUEST, 20102, 20121, 20141),
    VARIANTE_SIN_INVENTARIO(HttpStatus.CONFLICT, 20120),
    STOCK_INSUFICIENTE(HttpStatus.CONFLICT, 20122),
    AJUSTE_INVALIDO(HttpStatus.CONFLICT, 20123),
    OBSERVACION_REQUERIDA(HttpStatus.BAD_REQUEST, 20125),
    CLIENTE_INACTIVO(HttpStatus.FORBIDDEN, 20140, 20161),
    ITEM_NO_EN_CARRITO(HttpStatus.NOT_FOUND, 20143),
    RECURSO_OCUPADO(HttpStatus.SERVICE_UNAVAILABLE, 20144, 20168, 20188),

    // Pedidos y pagos
    PEDIDO_NO_ENCONTRADO(HttpStatus.NOT_FOUND, 20160),
    CARRITO_VACIO(HttpStatus.CONFLICT, 20162),
    DIRECCION_FACTURACION_INVALIDA(HttpStatus.CONFLICT, 20163),
    ESTADO_NO_PERMITE_OPERACION(HttpStatus.CONFLICT, 20164),
    MOTIVO_REQUERIDO(HttpStatus.BAD_REQUEST, 20165),
    PEDIDO_VENCIDO(HttpStatus.GONE, 20166),
    PASARELA_NO_DISPONIBLE(HttpStatus.SERVICE_UNAVAILABLE, 20167),
    PAGO_NO_ENCONTRADO(HttpStatus.NOT_FOUND, 20180),
    NOTIFICACION_INVALIDA(HttpStatus.BAD_REQUEST, 20181, 20183, 20184),
    REFERENCIA_EXTERNA_EN_CONFLICTO(HttpStatus.CONFLICT, 20182),
    EVENTO_NO_PROCESANDO(HttpStatus.CONFLICT, 20204),

    // Cuentas (registro y login). Los 203xx los lanzan los paquetes de cuentas: ver base-de-datos/auth-contrato-base-de-datos.md
    EMAIL_YA_REGISTRADO(HttpStatus.CONFLICT, 20300),
    DOCUMENTO_YA_REGISTRADO(HttpStatus.CONFLICT, 20301),
    TIPO_DOCUMENTO_INVALIDO(HttpStatus.BAD_REQUEST, 20302),

    // Administracion y catalogo (script 22): permisos de empleados, trabajadores, categorias, productos y variantes
    PERMISO_INSUFICIENTE(HttpStatus.FORBIDDEN, 20320),
    CUENTA_NO_GESTIONABLE(HttpStatus.CONFLICT, 20322),
    TRABAJADOR_NO_ENCONTRADO(HttpStatus.NOT_FOUND, 20323),
    PERMISO_INVALIDO(HttpStatus.BAD_REQUEST, 20324),
    CATEGORIA_NO_ENCONTRADA(HttpStatus.NOT_FOUND, 20330),
    CATEGORIA_PADRE_INVALIDO(HttpStatus.CONFLICT, 20331),
    CATEGORIA_REPETIDA(HttpStatus.CONFLICT, 20332),
    PRODUCTO_NO_ENCONTRADO(HttpStatus.NOT_FOUND, 20333),
    IMPUESTO_INVALIDO(HttpStatus.BAD_REQUEST, 20334),
    SKU_REPETIDO(HttpStatus.CONFLICT, 20335),
    VARIANTE_NO_ENCONTRADA(HttpStatus.NOT_FOUND, 20336),
    PRECIO_INVALIDO(HttpStatus.BAD_REQUEST, 20337),
    // Imagen del producto (script 24). La URL debe ser de nuestro bucket de Supabase.
    IMAGEN_URL_INVALIDA(HttpStatus.BAD_REQUEST, 20338),
    TIPO_IMAGEN_INVALIDO(HttpStatus.BAD_REQUEST),
    ALMACEN_IMAGENES_NO_CONFIGURADO(HttpStatus.SERVICE_UNAVAILABLE),
    ALMACEN_IMAGENES_NO_DISPONIBLE(HttpStatus.SERVICE_UNAVAILABLE),

    // Errores propios de la API (sin codigo Oracle)
    NO_ENCONTRADO(HttpStatus.NOT_FOUND),
    VALIDACION(HttpStatus.BAD_REQUEST),
    SOLICITUD_INVALIDA(HttpStatus.BAD_REQUEST),
    NO_AUTENTICADO(HttpStatus.UNAUTHORIZED),
    CREDENCIALES_INVALIDAS(HttpStatus.UNAUTHORIZED, 20304),
    CUENTA_INACTIVA(HttpStatus.FORBIDDEN),
    PASSWORD_DEBIL(HttpStatus.BAD_REQUEST),
    // 20321: quien llama no es un admin activo. 20325: el empleado que llama no existe o esta inactivo.
    ACCESO_DENEGADO(HttpStatus.FORBIDDEN, 20321, 20325),
    CLIENTE_NO_IDENTIFICADO(HttpStatus.FORBIDDEN),
    EMPLEADO_NO_IDENTIFICADO(HttpStatus.FORBIDDEN),
    DEMASIADAS_SOLICITUDES(HttpStatus.TOO_MANY_REQUESTS),
    CUERPO_DEMASIADO_GRANDE(HttpStatus.CONTENT_TOO_LARGE),
    PASARELA_NO_CONFIGURADA(HttpStatus.SERVICE_UNAVAILABLE);

    private static final Map<Integer, CodigoError> POR_CODIGO_ORACLE = new HashMap<>();

    static {
        for (CodigoError c : values()) {
            for (int codigo : c.codigosOracle) {
                POR_CODIGO_ORACLE.put(codigo, c);
            }
        }
    }

    private final HttpStatus estado;
    private final int[] codigosOracle;

    CodigoError(HttpStatus estado, int... codigosOracle) {
        this.estado = estado;
        this.codigosOracle = codigosOracle;
    }

    public HttpStatus estado() {
        return estado;
    }

    public Set<Integer> codigosOracle() {
        var codigos = new LinkedHashSet<Integer>();
        for (int c : codigosOracle) {
            codigos.add(c);
        }
        return Collections.unmodifiableSet(codigos);
    }

    public static Optional<CodigoError> deOracle(int codigoOracle) {
        return Optional.ofNullable(POR_CODIGO_ORACLE.get(codigoOracle));
    }
}
