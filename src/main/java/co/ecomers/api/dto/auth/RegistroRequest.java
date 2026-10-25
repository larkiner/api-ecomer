package co.ecomers.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Registro publico: siempre crea un CLIENTE. El personal no se auto-registra. */
public record RegistroRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 200, message = "El nombre admite hasta 200 caracteres")
        String nombre,

        @NotBlank(message = "El email es obligatorio")
        @Email(message = "El email no tiene un formato válido")
        @Size(max = 254, message = "El email admite hasta 254 caracteres")
        String email,

        @NotBlank(message = "El tipo de documento es obligatorio")
        @Pattern(regexp = "[A-Za-z]{2,10}", message = "El tipo de documento es el código, por ejemplo CC o NIT")
        String tipoDocumento,

        @NotBlank(message = "El número de documento es obligatorio")
        @Pattern(regexp = "[A-Za-z0-9-]{4,30}", message = "El número de documento solo admite letras, números y guion (4 a 30)")
        String numeroDocumento,

        @NotBlank(message = "El teléfono es obligatorio")
        @Pattern(regexp = "\\d{10}", message = "El teléfono debe tener exactamente 10 dígitos")
        String telefono,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String password
) { }
