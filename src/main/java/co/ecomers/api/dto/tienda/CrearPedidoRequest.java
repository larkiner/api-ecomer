package co.ecomers.api.dto.tienda;

import jakarta.validation.constraints.Positive;

/** Sin direccion se usa la direccion principal del cliente. */
public record CrearPedidoRequest(@Positive Long idDireccion) { }
