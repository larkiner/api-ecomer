package co.ecomers.api.dto.tienda;

import jakarta.validation.constraints.Size;
import co.ecomers.api.dto.comun.TextoPlano;

public record CancelarPedidoRequest(@Size(max = 400) @TextoPlano(multilinea = true) String motivo) { }
