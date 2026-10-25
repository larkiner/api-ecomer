package co.ecomers.api.controller.backoffice;

import co.ecomers.api.dto.backoffice.SolicitudSubidaRequest;
import co.ecomers.api.dto.backoffice.SubidaImagenResponse;
import co.ecomers.api.service.backoffice.DestinoImagen;
import co.ecomers.api.service.backoffice.ImagenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Imágenes de producto y de categoría. Esta API NO recibe la imagen: entrega una URL de subida firmada y el navegador
 * sube el archivo directo a Supabase, a la carpeta {@code productos/} o {@code categorias/}. Cada ruta exige el permiso
 * de su recurso ({@code PRODUCTOS} o {@code CATEGORIAS}, o admin), que declara {@code SecurityConfig}; la base lo vuelve
 * a exigir cuando la URL se asocia a un producto o a una categoría.
 */
@RestController
@RequestMapping("/api/v1/backoffice/imagenes")
public class ImagenController {

    private final ImagenService servicio;

    public ImagenController(ImagenService servicio) {
        this.servicio = servicio;
    }

    @PostMapping("/subida")
    @ResponseStatus(HttpStatus.CREATED)
    public SubidaImagenResponse solicitarSubida(@Valid @RequestBody SolicitudSubidaRequest req) {
        return servicio.solicitarSubida(req, DestinoImagen.PRODUCTOS);
    }

    @PostMapping("/categorias/subida")
    @ResponseStatus(HttpStatus.CREATED)
    public SubidaImagenResponse solicitarSubidaDeCategoria(@Valid @RequestBody SolicitudSubidaRequest req) {
        return servicio.solicitarSubida(req, DestinoImagen.CATEGORIAS);
    }
}
