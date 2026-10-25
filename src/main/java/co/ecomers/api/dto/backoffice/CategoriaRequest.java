package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Crear o editar una categoría. {@code idPadre} es opcional (sin padre = categoría raíz). {@code imagenUrl} es la
 * {@code urlPublica} que entregó {@code POST /backoffice/imagenes/subida}; al editar, si falta, la imagen se quita.
 */
public record CategoriaRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre admite hasta 100 caracteres")
        String nombre,

        Long idPadre,
        @Size(max = 500, message = "La URL de la imagen admite hasta 500 caracteres")
        String imagenUrl) { }
