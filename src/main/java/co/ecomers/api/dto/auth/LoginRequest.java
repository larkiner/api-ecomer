package co.ecomers.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // 72 es el maximo que BCrypt usa: acotarlo evita enviar cuerpos enormes al hash.
        @NotBlank @Size(max = 72) String password
) { }
