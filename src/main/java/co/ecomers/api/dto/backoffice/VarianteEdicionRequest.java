package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import co.ecomers.api.dto.comun.TextoPlano;

/** Editar el nombre y el precio de una variante. El cambio de precio queda en el historial con el correo del empleado. */
public record VarianteEdicionRequest(
        @NotBlank(message = "El nombre de la variante es obligatorio")
        @Size(max = 150, message = "El nombre de la variante admite hasta 150 caracteres")
        @TextoPlano String nombre,

        @NotNull(message = "El precio es obligatorio")
        @DecimalMin(value = "0", message = "El precio no puede ser negativo")
        @Digits(integer = 12, fraction = 2, message = "El precio admite hasta 12 enteros y 2 decimales")
        BigDecimal precioBase) { }
