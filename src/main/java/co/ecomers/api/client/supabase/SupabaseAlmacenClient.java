package co.ecomers.api.client.supabase;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Pide a Supabase Storage una URL de subida firmada para un archivo. El navegador sube la imagen DIRECTO a Supabase con esa
 * URL (un solo uso, valida unas horas): los bytes de la imagen nunca pasan por esta API y la clave de servicio nunca sale
 * del servidor.
 */
@Component
public class SupabaseAlmacenClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAlmacenClient.class);
    private static final String MENSAJE = "No se pudo preparar la subida de la imagen. Intenta de nuevo.";
    /** Tiempos de espera: si Supabase no responde, la peticion falla rapido en lugar de retener el hilo. */
    private static final Duration CONEXION = Duration.ofSeconds(5);
    private static final Duration LECTURA = Duration.ofSeconds(10);

    /** Lo que necesita el navegador para subir: el token y la URL completa a la que hacer el PUT. */
    public record SubidaFirmada(String token, String urlSubida) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RespuestaFirma(String url, String token) { }

    private final RestClient http;
    private final SupabaseProperties props;

    /**
     * Constructor de Spring. No se inyecta un {@code RestClient.Builder}: en Spring Boot 4 ese bean no se crea solo con el
     * starter web actual.
     */
    @Autowired
    public SupabaseAlmacenClient(SupabaseProperties props) {
        this(RestClient.builder().requestFactory(fabricaConTiempos()), props);
    }

    static JdkClientHttpRequestFactory fabricaConTiempos() {
        var fabrica = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONEXION).build());
        fabrica.setReadTimeout(LECTURA);
        return fabrica;
    }

    /** Para pruebas: permite enlazar un servidor simulado al builder. */
    SupabaseAlmacenClient(RestClient.Builder builder, SupabaseProperties props) {
        this.http = builder.build();
        this.props = props;
    }

    /** @param ruta ruta del archivo dentro del bucket, elegida por el servidor (por ejemplo {@code productos/<uuid>.png}) */
    public SubidaFirmada crearSubidaFirmada(String ruta) {
        String endpoint = props.url() + "/storage/v1/object/upload/sign/" + props.bucket() + "/" + ruta;
        RespuestaFirma respuesta;
        try {
            respuesta = http.post()
                    .uri(endpoint)
                    .headers(h -> {
                        h.set("apikey", props.serviceKey());
                        // Las claves nuevas (sb_secret_...) no son JWT: solo van en apikey.
                        if (props.serviceKey().startsWith("eyJ")) {
                            h.setBearerAuth(props.serviceKey());
                        }
                        h.set("x-upsert", "false");
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve()
                    .body(RespuestaFirma.class);
        } catch (RestClientException e) {
            // Sin el cuerpo de la respuesta ni la clave: solo el tipo de fallo.
            log.warn("Supabase Storage rechazo la subida firmada de {}: {}", ruta, e.getClass().getSimpleName());
            throw new NegocioException(CodigoError.ALMACEN_IMAGENES_NO_DISPONIBLE, MENSAJE);
        }

        String token = respuesta == null ? null : tokenDe(respuesta);
        if (token == null || token.isBlank()) {
            log.warn("Supabase Storage no devolvio un token de subida para {}", ruta);
            throw new NegocioException(CodigoError.ALMACEN_IMAGENES_NO_DISPONIBLE, MENSAJE);
        }
        return new SubidaFirmada(token, urlAbsoluta(respuesta.url(), ruta, token));
    }

    /** El token viene como campo o dentro del parametro {@code token} de la URL que devuelve Supabase. */
    private static String tokenDe(RespuestaFirma r) {
        if (r.token() != null && !r.token().isBlank()) {
            return r.token();
        }
        return r.url() == null ? null : UriComponentsBuilder.fromUriString(r.url()).build().getQueryParams().getFirst("token");
    }

    private String urlAbsoluta(String url, String ruta, String token) {
        if (url == null || url.isBlank()) {
            return props.url() + "/storage/v1/object/upload/sign/" + props.bucket() + "/" + ruta + "?token=" + token;
        }
        if (url.startsWith("http")) {
            return url;
        }
        return props.url() + "/storage/v1" + (url.startsWith("/") ? "" : "/") + url;
    }
}
