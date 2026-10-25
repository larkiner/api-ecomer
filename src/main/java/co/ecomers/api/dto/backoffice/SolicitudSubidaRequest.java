package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.NotBlank;

/** Pedir permiso para subir una imagen de producto. Solo se aceptan {@code image/jpeg}, {@code image/png} e {@code image/webp}. */
public record SolicitudSubidaRequest(@NotBlank(message = "tipoContenido es obligatorio") String tipoContenido) { }
