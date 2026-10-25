package co.ecomers.api.dto.backoffice;

import java.time.OffsetDateTime;

/** Evento de la cola sin resolver. {@code atascado}: lleva demasiado tiempo en PROCESANDO. */
public record EventoColaResponse(
        long idEvento,
        String tipo,
        String clave,
        String estado,
        int intentos,
        OffsetDateTime proximoIntento,
        OffsetDateTime fechaBloqueo,
        String ultimoError,
        OffsetDateTime fechaCreacion,
        boolean atascado) { }
