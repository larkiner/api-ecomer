package co.ecomers.api.service.backoffice;

import co.ecomers.api.client.supabase.SupabaseAlmacenClient;
import co.ecomers.api.client.supabase.SupabaseProperties;
import co.ecomers.api.dto.backoffice.SolicitudSubidaRequest;
import co.ecomers.api.dto.backoffice.SubidaImagenResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Imagenes de producto y de categoria. El navegador sube el archivo DIRECTO a Supabase Storage; esta clase hace solo
 * dos cosas: entregar una URL de subida firmada (cada destino en su carpeta: {@code productos/} y {@code categorias/}) y
 * comprobar que la URL que se guarda en el producto o la categoria sea de NUESTRO bucket, para que nadie pueda hacer
 * que la tienda muestre una imagen de un sitio ajeno. El permiso de cada destino lo exige {@code SecurityConfig}.
 */
@Service
public class ImagenService {

    /** Las URLs de subida de Supabase valen 2 horas. */
    static final long EXPIRA_SEGUNDOS = 7200;
    static final int URL_MAXIMA = 500;

    private static final Map<String, String> EXTENSIONES = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private static final Pattern RUTA_SEGURA = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_.-]*");

    private final SupabaseProperties props;
    private final SupabaseAlmacenClient almacen;

    public ImagenService(SupabaseProperties props, SupabaseAlmacenClient almacen) {
        this.props = props;
        this.almacen = almacen;
    }

    /**
     * La ruta dentro del bucket la elige el servidor (la carpeta del destino y un UUID), nunca el cliente: no hay forma de
     * pisar otros archivos.
     */
    public SubidaImagenResponse solicitarSubida(SolicitudSubidaRequest req, DestinoImagen destino) {
        exigirConfigurado();
        String tipo = req.tipoContenido() == null ? "" : req.tipoContenido().trim().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONES.get(tipo);
        if (extension == null) {
            throw new NegocioException(CodigoError.TIPO_IMAGEN_INVALIDO, "Solo se aceptan imágenes JPEG, PNG o WEBP");
        }
        String ruta = destino.carpeta() + "/" + UUID.randomUUID() + "." + extension;
        var subida = almacen.crearSubidaFirmada(ruta);
        return new SubidaImagenResponse(subida.urlSubida(), subida.token(), ruta, props.urlPublica(ruta), tipo, EXPIRA_SEGUNDOS);
    }

    /**
     * @return la URL recortada, o {@code null} si no hay imagen. Una URL que no sea de nuestro bucket (otro sitio, otro
     *         bucket, {@code http}, con {@code ..}, parametros, espacios o de mas de 500 caracteres) se rechaza.
     */
    public String validarUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        exigirConfigurado();
        String limpia = url.trim();
        String prefijo = props.prefijoPublico();
        if (limpia.length() > URL_MAXIMA || !limpia.startsWith(prefijo)) {
            throw invalida();
        }
        String resto = limpia.substring(prefijo.length());
        if (!RUTA_SEGURA.matcher(resto).matches() || resto.contains("..") || resto.contains("//") || resto.endsWith("/")) {
            throw invalida();
        }
        return limpia;
    }

    private void exigirConfigurado() {
        if (!props.configurado()) {
            throw new NegocioException(CodigoError.ALMACEN_IMAGENES_NO_CONFIGURADO,
                    "El almacenamiento de imágenes no está configurado en el servidor");
        }
    }

    private static NegocioException invalida() {
        return new NegocioException(CodigoError.IMAGEN_URL_INVALIDA,
                "La imagen debe ser un archivo subido a nuestro almacenamiento");
    }
}
