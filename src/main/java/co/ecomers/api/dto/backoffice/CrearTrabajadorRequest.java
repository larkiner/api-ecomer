package co.ecomers.api.dto.backoffice;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import co.ecomers.api.dto.comun.TextoPlano;

/** Alta de un trabajador por el admin. La contraseña inicial pasa por la política de contraseñas y se guarda con BCrypt. */
public record CrearTrabajadorRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 200, message = "El nombre admite hasta 200 caracteres")
        @TextoPlano String nombre,

        @NotBlank(message = "El email es obligatorio")
        @Email(message = "El email no tiene un formato válido")
        @Size(max = 254, message = "El email admite hasta 254 caracteres")
        String email,

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres")
        String password) { }
