package co.ecomers.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Conexion a Oracle: una URL y tres usuarios de aplicacion. Si falta una clave la app no arranca
 * (fail-closed): el placeholder sin resolver o el valor vacio detienen el arranque.
 */
@Validated
@ConfigurationProperties("app.datasource")
public record BaseDatosProperties(
        @NotBlank String url,
        @NotNull Duration connectionTimeout,
        @Valid @NotNull Usuario cliente,
        @Valid @NotNull Usuario empleado,
        @Valid @NotNull Usuario integracion) {

    public record Usuario(
            @NotBlank String username,
            @NotBlank String password,
            @Min(1) int maximumPoolSize,
            @Min(0) int minimumIdle) {

        /** Nunca imprimir la clave (logs, errores de arranque, actuator). */
        @Override
        public String toString() {
            return "Usuario[username=" + username + ", password=***, maximumPoolSize=" + maximumPoolSize
                    + ", minimumIdle=" + minimumIdle + "]";
        }
    }
}
