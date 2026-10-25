package co.ecomers.api.client.supabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** El cliente que pide a Supabase Storage una URL de subida firmada (sin enviar nunca la imagen por la API). */
class SupabaseAlmacenClientTest {

    private static final String BASE = "https://abc.supabase.co";
    private static final String RUTA = "productos/11111111-2222-3333-4444-555555555555.png";
    private static final String ENDPOINT = BASE + "/storage/v1/object/upload/sign/productos/" + RUTA;

    private MockRestServiceServer servidor;
    private SupabaseAlmacenClient cliente;

    @BeforeEach
    void preparar() {
        var builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        cliente = new SupabaseAlmacenClient(builder, new SupabaseProperties(BASE, "sb_secret_clave", "productos"));
    }

    @Test
    void pideLaSubidaFirmadaConLaClaveDeServicioYDevuelveElTokenYLaUrlAbsoluta() {
        servidor.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", "sb_secret_clave"))
                .andExpect(header("x-upsert", "false"))
                .andRespond(withSuccess("{\"url\":\"/object/upload/sign/productos/" + RUTA + "?token=abc.def.ghi\"}",
                        MediaType.APPLICATION_JSON));

        var subida = cliente.crearSubidaFirmada(RUTA);

        assertThat(subida.token()).isEqualTo("abc.def.ghi");
        assertThat(subida.urlSubida())
                .isEqualTo(BASE + "/storage/v1/object/upload/sign/productos/" + RUTA + "?token=abc.def.ghi");
        servidor.verify();
    }

    @Test
    void conUnaClaveJwtLaEnviaTambienComoBearer() {
        var builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).build();
        var conJwt = new SupabaseAlmacenClient(builder, new SupabaseProperties(BASE, "eyJhbGciOi.payload.firma", "productos"));
        servidor.expect(requestTo(ENDPOINT))
                .andExpect(header("Authorization", "Bearer eyJhbGciOi.payload.firma"))
                .andRespond(withSuccess("{\"url\":\"/object/upload/sign/x?token=t\"}", MediaType.APPLICATION_JSON));

        assertThat(conJwt.crearSubidaFirmada(RUTA).token()).isEqualTo("t");
        servidor.verify();
    }

    @Test
    void tambienAceptaElTokenComoCampoAparte() {
        servidor.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("{\"url\":\"/object/upload/sign/x\",\"token\":\"solo-campo\"}", MediaType.APPLICATION_JSON));

        assertThat(cliente.crearSubidaFirmada(RUTA).token()).isEqualTo("solo-campo");
    }

    @Test
    void siSupabaseFallaOLaRespuestaNoTraeTokenElErrorNoFiltraLaClaveNiElCuerpo() {
        servidor.expect(requestTo(ENDPOINT)).andRespond(withServerError().body("secreto: sb_secret_clave"));
        assertThatThrownBy(() -> cliente.crearSubidaFirmada(RUTA))
                .isInstanceOf(NegocioException.class)
                .hasMessageNotContaining("sb_secret_clave").hasMessageNotContaining("secreto")
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.ALMACEN_IMAGENES_NO_DISPONIBLE);

        servidor.reset();
        servidor.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"url\":\"/object/upload/sign/x\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> cliente.crearSubidaFirmada(RUTA))
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.ALMACEN_IMAGENES_NO_DISPONIBLE);

        servidor.reset();
        servidor.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> cliente.crearSubidaFirmada(RUTA))
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.ALMACEN_IMAGENES_NO_DISPONIBLE);
    }

    @Test
    void laConfiguracionNuncaImprimeLaClaveYArmaLasUrlsPublicas() {
        var props = new SupabaseProperties(BASE + "/", "sb_secret_clave", " ");

        assertThat(props.toString()).doesNotContain("sb_secret_clave");
        assertThat(props.url()).isEqualTo(BASE);
        assertThat(props.bucket()).isEqualTo("productos");
        assertThat(props.configurado()).isTrue();
        assertThat(props.prefijoPublico()).isEqualTo(BASE + "/storage/v1/object/public/productos/");
        assertThat(props.urlPublica("productos/a.png")).isEqualTo(BASE + "/storage/v1/object/public/productos/productos/a.png");
        assertThat(new SupabaseProperties("", "clave", "p").configurado()).isFalse();
        assertThat(new SupabaseProperties(BASE, "", "p").configurado()).isFalse();
        assertThat(new SupabaseProperties(null, null, null).configurado()).isFalse();
    }
}
