package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Editar los datos de un producto (las variantes se editan aparte). */
public record ProductoEdicionRequest(
        @NotNull(message = "idCategoria es obligatorio")
        Long idCategoria,

        @NotNull(message = "idImpuesto es obligatorio")
        Long idImpuesto,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 200, message = "El nombre admite hasta 200 caracteres")
        String nombre,

        @Size(max = 2000, message = "La descripción admite hasta 2000 caracteres")
        String descripcion,

        @Size(max = 500, message = "La URL de la imagen admite hasta 500 caracteres")
        String imagenUrl) { }
