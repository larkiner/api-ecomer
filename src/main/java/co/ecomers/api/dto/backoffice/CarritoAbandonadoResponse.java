package co.ecomers.api.dto.backoffice;

import java.time.OffsetDateTime;

public record CarritoAbandonadoResponse(
        long idCarrito,
        long idCliente,
        String nombre,
        String email,
        OffsetDateTime ultimaActividad,
        int items) { }
