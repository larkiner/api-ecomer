package co.ecomers.api.dto.tienda;

import jakarta.validation.constraints.Size;

public record CancelarPedidoRequest(@Size(max = 400) String motivo) { }
