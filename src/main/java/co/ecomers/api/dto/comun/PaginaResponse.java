package co.ecomers.api.dto.comun;

import java.util.List;

/** Pagina de resultados: {@code pagina} empieza en 0. */
public record PaginaResponse<T>(List<T> contenido, int pagina, int tamano, long total) { }
