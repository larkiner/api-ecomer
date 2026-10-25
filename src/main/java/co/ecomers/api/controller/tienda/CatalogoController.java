package co.ecomers.api.controller.tienda;

import co.ecomers.api.dto.comun.PaginaResponse;
import co.ecomers.api.dto.tienda.CategoriaPublicaResponse;
import co.ecomers.api.dto.tienda.VarianteResponse;
import co.ecomers.api.service.tienda.CatalogoService;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalogo publico. Sale con Cache-Control explicito: sin el, Spring Security marca la respuesta
 * no-store y cada visita llegaria hasta Oracle.
 */
@RestController
@RequestMapping("/api/v1/catalogo")
public class CatalogoController {

    private final CatalogoService servicio;
    private final CacheControl cacheControl;

    public CatalogoController(CatalogoService servicio, @Value("${app.cache.http-max-age-seg:60}") long maxAgeSeg) {
        this.servicio = servicio;
        this.cacheControl = CacheControl.maxAge(Duration.ofSeconds(maxAgeSeg)).cachePublic();
    }

    @GetMapping("/variantes")
    public ResponseEntity<PaginaResponse<VarianteResponse>> buscar(@RequestParam(required = false) String q,
                                                                   @RequestParam(required = false) Long categoria,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(cacheControl).body(servicio.buscar(q, categoria, page, size));
    }

    @GetMapping("/categorias")
    public ResponseEntity<List<CategoriaPublicaResponse>> categorias() {
        return ResponseEntity.ok().cacheControl(cacheControl).body(servicio.listarCategorias());
    }

    @GetMapping("/variantes/{idVariante}")
    public ResponseEntity<VarianteResponse> obtener(@PathVariable long idVariante) {
        return ResponseEntity.ok().cacheControl(cacheControl).body(servicio.obtener(idVariante));
    }
}
