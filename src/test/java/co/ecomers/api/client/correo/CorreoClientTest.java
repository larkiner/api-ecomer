package co.ecomers.api.client.correo;

import static org.assertj.core.api.Assertions.assertThat;

import co.ecomers.api.client.erp.ErpSimuladoClient;
import co.ecomers.api.model.PedidoParaNotificar;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Correo real contra un SMTP de prueba (GreenMail) con las plantillas de produccion. */
class CorreoClientTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    private CorreoClient correo;

    @BeforeEach
    void preparar() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var motor = new SpringTemplateEngine();
        motor.setTemplateResolver(resolver);

        var mail = new JavaMailSenderImpl();
        mail.setHost("localhost");
        mail.setPort(greenMail.getSmtp().getPort());
        mail.setDefaultEncoding("UTF-8");
        correo = new CorreoClient(mail, motor, "tienda@ecomers.test");
    }

    @Test
    void pedidoPagadoConMontosEnFormatoColombiano() throws Exception {
        var linea = new PedidoParaNotificar.Linea("LAP-001", "Portátil X", 1, new BigDecimal("238000.00"));
        correo.enviar("ana@correo.co", "Tu pedido #45 está pagado", "correo/pedido-pagado", Map.of(
                "nombre", "Ana Pérez", "idPedido", 45L, "lineas", List.of(linea),
                "subtotal", new BigDecimal("200000.00"), "impuesto", new BigDecimal("38000.00"),
                "total", new BigDecimal("238000.00")));

        MimeMessage[] recibidos = greenMail.getReceivedMessages();
        assertThat(recibidos).hasSize(1);
        assertThat(recibidos[0].getSubject()).isEqualTo("Tu pedido #45 está pagado");
        assertThat(recibidos[0].getAllRecipients()[0].toString()).isEqualTo("ana@correo.co");
        assertThat(recibidos[0].getFrom()[0].toString()).isEqualTo("tienda@ecomers.test");
        assertThat((String) recibidos[0].getContent()).contains("Ana Pérez").contains("Portátil X").contains("LAP-001")
                .contains("238.000,00").contains("#45");
    }

    @Test
    void elHtmlDelNombreSeEscapa() throws Exception {
        correo.enviar("ana@correo.co", "x", "correo/carrito-abandonado", Map.of("nombre", "<script>alert(1)</script>"));

        String cuerpo = (String) greenMail.getReceivedMessages()[0].getContent();
        assertThat(cuerpo).doesNotContain("<script>").contains("&lt;script&gt;").contains("carrito");
    }

    @Test
    void erpSimuladoEsIdempotentePorPedido() {
        var pedido = new PedidoParaNotificar(45L, "Ana", "a@b.co", BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, List.of());
        var erp = new ErpSimuladoClient();
        assertThat(erp.enviarPedido(pedido)).isEqualTo("ERP-45").isEqualTo(erp.enviarPedido(pedido));
    }
}
