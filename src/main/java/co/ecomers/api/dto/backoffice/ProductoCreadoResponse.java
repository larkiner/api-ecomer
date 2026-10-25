package co.ecomers.api.dto.backoffice;

import java.util.List;

/** El producto creado y los IDs de sus variantes, en el orden en que se enviaron. */
public record ProductoCreadoResponse(long idProducto, List<Long> idsVariantes) { }
