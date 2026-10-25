package co.ecomers.api.dto.backoffice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import co.ecomers.api.dto.comun.TextoPlano;
import jakarta.validation.constraints.Positive;

/** Crear un producto con sus variantes en una sola operación: queda vendible. Al menos una variante. */
public record ProductoRequest(
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
        String imagenUrl,

        @NotEmpty(message = "El producto necesita al menos una variante")
        @Size(max = 50, message = "Un producto admite hasta 50 variantes por petición")
        List<@Valid VarianteRequest> variantes) { }
