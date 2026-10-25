package co.ecomers.api.dto.backoffice;

import java.util.List;

/** Un producto con todas sus variantes (activas e inactivas), para administrarlo. */
public record ProductoResponse(
        long idProducto,
        long idCategoria,
        long idImpuesto,
        String nombre,
        String descripcion,
        String imagenUrl,
        boolean activo,
        List<VarianteResponse> variantes) { }
