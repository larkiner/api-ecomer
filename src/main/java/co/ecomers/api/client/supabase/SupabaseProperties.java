package co.ecomers.api.client.supabase;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracion de Supabase Storage para las imagenes de producto. Es opcional para arrancar: sin {@code url} y
 * {@code serviceKey} la API responde 503 al pedir una subida y rechaza cualquier URL de imagen. La clave de servicio
 * vive SOLO en el servidor (nunca en el frontend). El bucket debe ser publico para que la tienda muestre las fotos.
 */
@ConfigurationProperties("app.supabase")
public record SupabaseProperties(String url, String serviceKey, String bucket) {

    public SupabaseProperties {
        url = url == null ? "" : url.trim().replaceAll("/+$", "");
        serviceKey = serviceKey == null ? "" : serviceKey.trim();
        bucket = bucket == null || bucket.isBlank() ? "productos" : bucket.trim();
    }

    public boolean configurado() {
        return !url.isEmpty() && !serviceKey.isEmpty();
    }

    /** Comienzo de toda URL publica valida del bucket: {@code <url>/storage/v1/object/public/<bucket>/}. */
    public String prefijoPublico() {
        return url + "/storage/v1/object/public/" + bucket + "/";
    }

    public String urlPublica(String ruta) {
        return prefijoPublico() + ruta;
    }

    /** Nunca imprimir la clave (logs, actuator). */
    @Override
    public String toString() {
        return "SupabaseProperties[url=" + url + ", bucket=" + bucket + ", serviceKey=***]";
    }
}
