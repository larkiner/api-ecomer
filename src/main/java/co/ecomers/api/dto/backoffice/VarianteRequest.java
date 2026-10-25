package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import co.ecomers.api.dto.comun.TextoPlano;
import jakarta.validation.constraints.Pattern;

/**
 * Una variante (SKU y precio) de un producto. El SKU se normaliza a mayúsculas en la base. {@code controlaStock} se fija
 * al crearla y no cambia después. El precio admite hasta 12 enteros y 2 decimales (la columna es NUMBER(14,2)).
 */
public record VarianteRequest(
        @NotBlank(message = "El SKU es obligatorio")
        @Size(max = 50, message = "El SKU admite hasta 50 caracteres")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,49}",
                message = "El SKU solo admite letras, números, punto, guion y guion bajo") String sku,

        @NotBlank(message = "El nombre de la variante es obligatorio")
        @Size(max = 150, message = "El nombre de la variante admite hasta 150 caracteres")
        @TextoPlano String nombre,

        @NotNull(message = "El precio es obligatorio")
        @DecimalMin(value = "0", message = "El precio no puede ser negativo")
        @Digits(integer = 12, fraction = 2, message = "El precio admite hasta 12 enteros y 2 decimales")
        BigDecimal precioBase,

        @NotNull(message = "controlaStock es obligatorio")
        Boolean controlaStock) { }
