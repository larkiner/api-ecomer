package co.ecomers.api.dto.tienda;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Una categoría activa del catálogo público. {@code idPadre} se omite en las categorías raíz e {@code imagenUrl} en las
 * que no tienen imagen.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoriaPublicaResponse(long idCategoria, Long idPadre, String nombre, String imagenUrl) { }
