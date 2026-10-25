package co.ecomers.api.service.tienda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.ecomers.api.client.wompi.CheckoutWompi;
import co.ecomers.api.client.wompi.WompiProperties;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.ClienteActual;
import co.ecomers.api.model.IntentoPago;
import co.ecomers.api.repository.tienda.PagoRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PagoServiceTest {

    private static final ClienteActual CLIENTE = new ClienteActual(7L, "ana@correo.com");
    private static final String REF = "A1B2C3D4E5F60718293A4B5C6D7E8F90";

    private final PagoRepository repo = mock(PagoRepository.class);

    private PagoService servicio(String publicKey, String integrity) {
        var props = new WompiProperties(publicKey, integrity, "", null, "http://localhost:5173/pedidos/{idPedido}/resultado");
        return new PagoService(repo, new CheckoutWompi(props));
    }

    @Test
    void iniciaElPagoEnWompiConElMontoDeLaBase() {
        when(repo.iniciar(CLIENTE, 10L, "WOMPI")).thenReturn(new IntentoPago(55L, REF, new BigDecimal("238000")));

        var r = servicio("pub_test_x", "test_integrity_secreto").iniciar(CLIENTE, 10L);

        assertThat(r.referencia()).isEqualTo(REF);
        assertThat(r.montoEnCentavos()).isEqualTo(23_800_000L);
        assertThat(r.moneda()).isEqualTo("COP");
        assertThat(r.publicKey()).isEqualTo("pub_test_x");
        // printf '%s' REF 23800000 COP test_integrity_secreto | sha256sum
        assertThat(r.firmaIntegridad()).isEqualTo("e355cc35372af7fc91cf42f613f1695102cf17219dd3a90f0dad1d02aa54bd27");
        assertThat(r.redirectUrl()).isEqualTo("http://localhost:5173/pedidos/10/resultado");
        assertThat(r.checkoutUrl()).startsWith("https://checkout.wompi.co/p/?public-key=pub_test_x")
                .contains("reference=" + REF).contains("amount-in-cents=23800000");
    }

    @Test
    void sinLlavesDeWompiNoCreaIntentosEnLaBase() {
        assertThatThrownBy(() -> servicio("", "").iniciar(CLIENTE, 10L))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.PASARELA_NO_CONFIGURADA);
        verifyNoInteractions(repo);
    }

    @Test
    void pedidoNoPositivoEsNoEncontrado() {
        assertThatThrownBy(() -> servicio("pub_test_x", "s").iniciar(CLIENTE, 0L))
                .isInstanceOf(NegocioException.class)
                .extracting(e -> ((NegocioException) e).codigo()).isEqualTo(CodigoError.PEDIDO_NO_ENCONTRADO);
        verifyNoInteractions(repo);
    }
}
