package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import co.ecomers.api.dto.comun.TextoPlano;

/** Mercancia que entra al inventario (compra a proveedor, devolucion de cliente revisada, etc.). */
public record EntradaInventarioRequest(
        @NotNull @Min(1) @Max(100_000) Integer cantidad,
        @Size(max = 500) @TextoPlano(multilinea = true) String observacion) { }
