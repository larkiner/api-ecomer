package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import co.ecomers.api.dto.comun.TextoPlano;

/** Correccion del stock con signo (mermas, conteo fisico). La observacion es obligatoria. */
public record AjusteInventarioRequest(
        @NotNull @Min(-100_000) @Max(100_000) Integer cantidad,
        @NotBlank @Size(max = 500) @TextoPlano(multilinea = true) String observacion) { }
