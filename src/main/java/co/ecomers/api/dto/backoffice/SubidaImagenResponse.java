package co.ecomers.api.dto.backoffice;

/**
 * Lo que el frontend necesita para subir la imagen DIRECTO a Supabase: {@code urlSubida} (con el token ya incluido; se
 * sube con {@code PUT}) o, si usa supabase-js, {@code ruta} + {@code token} con {@code uploadToSignedUrl}. Al terminar,
 * {@code urlPublica} es lo que se envía como {@code imagenUrl} al crear o editar el producto.
 */
public record SubidaImagenResponse(
        String urlSubida,
        String token,
        String ruta,
        String urlPublica,
        String tipoContenido,
        long expiraEnSegundos) { }
