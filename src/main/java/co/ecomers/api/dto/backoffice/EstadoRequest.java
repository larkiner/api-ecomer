package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.NotNull;

/** Activar ({@code true}) o desactivar ({@code false}) un registro. */
public record EstadoRequest(@NotNull(message = "activo es obligatorio") Boolean activo) { }
