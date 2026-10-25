package co.ecomers.api.dto.backoffice;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Una categoría del catálogo (incluye las inactivas). {@code idPadre} se omite en las categorías raíz e
 * {@code imagenUrl} en las que no tienen imagen.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoriaResponse(long idCategoria, Long idPadre, String nombre, boolean activo, String imagenUrl) { }
