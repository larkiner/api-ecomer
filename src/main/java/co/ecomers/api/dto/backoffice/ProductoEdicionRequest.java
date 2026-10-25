package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import co.ecomers.api.dto.comun.TextoPlano;
import jakarta.validation.constraints.Positive;

/** Editar los datos de un producto (las variantes se editan aparte). */
public record ProductoEdicionRequest(
        @NotNull(message = "idCategoria es obligatorio")
        @Positive(message = "idCategoria debe ser un número positivo") Long idCategoria,

        @NotNull(message = "idImpuesto es obligatorio")
        @Positive(message = "idImpuesto debe ser un número positivo") Long idImpuesto,

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 200, message = "El nombre admite hasta 200 caracteres")
        @TextoPlano String nombre,

        @Size(max = 2000, message = "La descripción admite hasta 2000 caracteres")
        @TextoPlano(multilinea = true) String descripcion,

        @Size(max = 500, message = "La URL de la imagen admite hasta 500 caracteres")
        String imagenUrl) { }
