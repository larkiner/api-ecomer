package co.ecomers.api.dto.backoffice;

import java.time.OffsetDateTime;
import java.util.List;

/** Un trabajador con los códigos de los permisos que tiene asignados. */
public record TrabajadorResponse(
        long idEmpleado,
        String nombre,
        String email,
        boolean activo,
        OffsetDateTime fechaCreacion,
        List<String> permisos) { }
