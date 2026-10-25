package co.ecomers.api.dto.tienda;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AgregarItemRequest(
        @NotNull @Positive Long idVariante,
        @NotNull @Min(1) @Max(999) Integer cantidad) { }
