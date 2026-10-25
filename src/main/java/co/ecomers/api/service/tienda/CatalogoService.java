package co.ecomers.api.service.tienda;

import co.ecomers.api.dto.comun.PaginaResponse;
import co.ecomers.api.dto.tienda.CategoriaPublicaResponse;
import co.ecomers.api.dto.tienda.VarianteResponse;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.repository.tienda.CatalogoRepository;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Catalogo publico. Se cachea unos segundos (app.cache.catalogo-ttl-seg): se lee mucho y cambia
 * poco, y un precio un poco viejo nunca cambia un cobro, porque el pedido relee y congela los
 * precios en la base al crearse.
 */
@Service
public class CatalogoService {

    public static final String CACHE = "catalogo";
    static final int TAMANO_MAXIMO = 50;
    static final int PAGINA_MAXIMA = 100_000;
    static final int BUSQUEDA_MAXIMA = 100;

    private final CatalogoRepository repo;

    public CatalogoService(CatalogoRepository repo) {
        this.repo = repo;
    }

    @Cacheable(cacheNames = CACHE, key = "'pagina:' + #busqueda + ':' + #categoria + ':' + #pagina + ':' + #tamano")
    public PaginaResponse<VarianteResponse> buscar(String busqueda, Long categoria, int pagina, int tamano) {
        if (pagina < 0 || pagina > PAGINA_MAXIMA || tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new NegocioException(CodigoError.VALIDACION,
                    "page debe estar entre 0 y " + PAGINA_MAXIMA + " y size entre 1 y " + TAMANO_MAXIMO);
        }
        if (categoria != null && categoria <= 0) {
            throw new NegocioException(CodigoError.VALIDACION, "categoria debe ser un ID positivo");
        }
        String texto = normalizar(busqueda);
        long total = repo.contar(texto, categoria);
        int offset = pagina * tamano;
        List<VarianteResponse> contenido = offset >= total ? List.of() : repo.listar(texto, categoria, offset, tamano);
        return new PaginaResponse<>(contenido, pagina, tamano, total);
    }

    /** Las categorias activas con su imagen. Solo lo que existe en la base: la tienda no inventa categorias. */
    @Cacheable(cacheNames = CACHE, key = "'categorias'")
    public List<CategoriaPublicaResponse> listarCategorias() {
        return repo.listarCategorias();
    }

    @Cacheable(cacheNames = CACHE, key = "'variante:' + #idVariante")
    public VarianteResponse obtener(long idVariante) {
        if (idVariante <= 0) {
            throw noEncontrada();
        }
        return repo.buscar(idVariante).orElseThrow(CatalogoService::noEncontrada);
    }

    private static String normalizar(String busqueda) {
        if (busqueda == null || busqueda.isBlank()) {
            return null;
        }
        String texto = busqueda.trim();
        if (texto.length() > BUSQUEDA_MAXIMA) {
            throw new NegocioException(CodigoError.VALIDACION,
                    "La búsqueda admite hasta " + BUSQUEDA_MAXIMA + " caracteres");
        }
        return texto;
    }

    private static NegocioException noEncontrada() {
        return new NegocioException(CodigoError.NO_ENCONTRADO, "Variante no encontrada");
    }
}
