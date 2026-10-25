package co.ecomers.api.service.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.client.supabase.SupabaseAlmacenClient;
import co.ecomers.api.client.supabase.SupabaseAlmacenClient.SubidaFirmada;
import co.ecomers.api.client.supabase.SupabaseProperties;
import co.ecomers.api.dto.backoffice.SolicitudSubidaRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImagenServiceTest {

    private static final String BASE = "https://abc.supabase.co";
    private static final String PUBLICA = BASE + "/storage/v1/object/public/productos/";

    private final SupabaseAlmacenClient almacen = mock(SupabaseAlmacenClient.class);
    private final ImagenService servicio = new ImagenService(new SupabaseProperties(BASE, "clave-secreta", "productos"), almacen);
    private final ImagenService sinConfigurar = new ImagenService(new SupabaseProperties("", "", ""), almacen);

    private static void esperaError(Runnable accion, CodigoError esperado) {
        assertThatThrownBy(accion::run).isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(esperado);
    }

    // ---------------- Validar la URL que guarda el producto ----------------

    @Test
    void sinImagenSeAceptaNuloOVacioYSeConvierteEnNulo() {
        assertThat(servicio.validarUrl(null)).isNull();
        assertThat(servicio.validarUrl("")).isNull();
        assertThat(servicio.validarUrl("   ")).isNull();
        assertThat(sinConfigurar.validarUrl(null)).isNull();
    }

    @Test
    void unaUrlDelBucketDeSupabaseSeAceptaYSeRecorta() {
        assertThat(servicio.validarUrl("  " + PUBLICA + "productos/lap-1.png ")).isEqualTo(PUBLICA + "productos/lap-1.png");
        assertThat(servicio.validarUrl(PUBLICA + "a/b_c-d.WEBP")).isEqualTo(PUBLICA + "a/b_c-d.WEBP");
    }

    @Test
    void lasUrlsDeOtroSitioOtroBucketOSinHttpsSeRechazan() {
        for (String mala : new String[] {
                "https://evil.example.com/foto.png",
                "http://abc.supabase.co/storage/v1/object/public/productos/a.png",
                "https://abc.supabase.co/storage/v1/object/public/otro-bucket/a.png",
                "https://abc.supabase.co.evil.com/storage/v1/object/public/productos/a.png",
                "https://abc.supabase.co/storage/v1/object/public/productos",
                "javascript:alert(1)",
                "data:image/png;base64,AAAA"}) {
            esperaError(() -> servicio.validarUrl(mala), CodigoError.IMAGEN_URL_INVALIDA);
        }
    }

    @Test
    void rutasPeligrosasConPuntosParametrosOEspaciosSeRechazan() {
        for (String resto : new String[] {"../../secreto.png", "a/../b.png", "a.png?token=1", "a.png#x", "a b.png",
                "a.png/", "/a.png", "a%2e%2e/b.png", "<script>.png"}) {
            esperaError(() -> servicio.validarUrl(PUBLICA + resto), CodigoError.IMAGEN_URL_INVALIDA);
        }
    }

    @Test
    void unaUrlDeMasDe500CaracteresSeRechaza() {
        esperaError(() -> servicio.validarUrl(PUBLICA + "a".repeat(500)), CodigoError.IMAGEN_URL_INVALIDA);
    }

    @Test
    void sinSupabaseConfiguradoUnaUrlNoSeAceptaYElErrorDiceQueFaltaConfigurar() {
        esperaError(() -> sinConfigurar.validarUrl(PUBLICA + "a.png"), CodigoError.ALMACEN_IMAGENES_NO_CONFIGURADO);
    }

    // ---------------- Pedir una subida firmada ----------------

    @Test
    void laRutaLaEligeElServidorConUnUuidYLaExtensionDelTipo() {
        when(almacen.crearSubidaFirmada(anyString())).thenReturn(new SubidaFirmada("tok", BASE + "/storage/v1/object/upload/sign/x?token=tok"));

        var respuesta = servicio.solicitarSubida(new SolicitudSubidaRequest("image/png"), DestinoImagen.PRODUCTOS);

        var ruta = ArgumentCaptor.forClass(String.class);
        verify(almacen).crearSubidaFirmada(ruta.capture());
        assertThat(ruta.getValue()).matches("productos/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png");
        assertThat(respuesta.ruta()).isEqualTo(ruta.getValue());
        assertThat(respuesta.urlPublica()).isEqualTo(PUBLICA + ruta.getValue());
        assertThat(respuesta.token()).isEqualTo("tok");
        assertThat(respuesta.urlSubida()).contains("?token=tok");
        assertThat(respuesta.tipoContenido()).isEqualTo("image/png");
        assertThat(respuesta.expiraEnSegundos()).isEqualTo(7200L);
        // La URL publica que devuelve es justo una que luego se acepta al guardar el producto
        assertThat(servicio.validarUrl(respuesta.urlPublica())).isEqualTo(respuesta.urlPublica());
    }

    @Test
    void cadaDestinoTieneSuCarpetaEnElBucketYSuUrlPublicaSeAceptaAlGuardar() {
        when(almacen.crearSubidaFirmada(anyString())).thenReturn(new SubidaFirmada("t", "u"));

        var categoria = servicio.solicitarSubida(new SolicitudSubidaRequest("image/webp"), DestinoImagen.CATEGORIAS);
        var producto = servicio.solicitarSubida(new SolicitudSubidaRequest("image/webp"), DestinoImagen.PRODUCTOS);

        assertThat(categoria.ruta()).matches("categorias/[0-9a-f-]{36}\\.webp");
        assertThat(producto.ruta()).matches("productos/[0-9a-f-]{36}\\.webp");
        assertThat(categoria.urlPublica()).isEqualTo(PUBLICA + categoria.ruta());
        assertThat(servicio.validarUrl(categoria.urlPublica())).isEqualTo(categoria.urlPublica());
    }

    @Test
    void soloSePermitenJpegPngYWebpYCadaTipoTieneSuExtension() {
        when(almacen.crearSubidaFirmada(anyString())).thenReturn(new SubidaFirmada("t", "u"));

        assertThat(servicio.solicitarSubida(new SolicitudSubidaRequest("image/jpeg"), DestinoImagen.PRODUCTOS).ruta()).endsWith(".jpg");
        assertThat(servicio.solicitarSubida(new SolicitudSubidaRequest("IMAGE/WEBP"), DestinoImagen.PRODUCTOS).ruta()).endsWith(".webp");
        assertThat(servicio.solicitarSubida(new SolicitudSubidaRequest(" image/png "), DestinoImagen.PRODUCTOS).ruta()).endsWith(".png");
    }

    @Test
    void otroTipoDeArchivoNoPidePermisoASupabase() {
        for (String malo : new String[] {"image/svg+xml", "image/gif", "text/html", "application/pdf", "image/png; x=1", "png", ""}) {
            esperaError(() -> servicio.solicitarSubida(new SolicitudSubidaRequest(malo), DestinoImagen.PRODUCTOS), CodigoError.TIPO_IMAGEN_INVALIDO);
        }
        verifyNoInteractions(almacen);
    }

    @Test
    void sinSupabaseConfiguradoNoSePuedePedirLaSubida() {
        esperaError(() -> sinConfigurar.solicitarSubida(new SolicitudSubidaRequest("image/png"), DestinoImagen.PRODUCTOS),
                CodigoError.ALMACEN_IMAGENES_NO_CONFIGURADO);
        verifyNoInteractions(almacen);
    }
}
