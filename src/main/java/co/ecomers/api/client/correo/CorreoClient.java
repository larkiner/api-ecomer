package co.ecomers.api.client.correo;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/** Envio de correos HTML con plantillas Thymeleaf (src/main/resources/templates/correo). */
@Component
public class CorreoClient {

    private final JavaMailSender mail;
    private final ITemplateEngine motor;
    private final String remitente;

    public CorreoClient(JavaMailSender mail, ITemplateEngine motor,
                        @Value("${app.correo.remitente:tienda@ecomers.test}") String remitente) {
        this.mail = mail;
        this.motor = motor;
        this.remitente = remitente;
    }

    /** Renderiza la plantilla (por ejemplo "correo/pedido-pagado") y envia el HTML. */
    public void enviar(String para, String asunto, String plantilla, Map<String, Object> modelo) {
        var contexto = new Context();
        contexto.setVariables(modelo);
        String html = motor.process(plantilla, contexto);
        try {
            MimeMessage mensaje = mail.createMimeMessage();
            var ayudante = new MimeMessageHelper(mensaje, StandardCharsets.UTF_8.name());
            ayudante.setFrom(remitente);
            ayudante.setTo(para);
            ayudante.setSubject(asunto);
            ayudante.setText(html, true);
            mail.send(mensaje);
        } catch (MessagingException e) {
            throw new IllegalStateException("No se pudo armar el correo para " + para, e);
        }
    }
}
