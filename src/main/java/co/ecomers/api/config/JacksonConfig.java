package co.ecomers.api.config;

import java.math.BigDecimal;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Configuration
public class JacksonConfig {

    /** El dinero viaja como texto decimal ("238000.00"): un numero JSON en JavaScript pierde precision. */
    @Bean
    public JsonMapperBuilderCustomizer dineroComoTexto() {
        return builder -> builder.addModule(new SimpleModule("dinero").addSerializer(BigDecimal.class, ToStringSerializer.instance));
    }

    /**
     * Un entero (ID, cantidad) enviado como {@code 1.5} se rechaza con 400. Por defecto Jackson lo truncaria a {@code 1} en
     * silencio, y una peticion distinta de la que quiso el cliente se ejecutaria sin avisar.
     */
    @Bean
    public JsonMapperBuilderCustomizer enterosSinTruncar() {
        return builder -> builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
