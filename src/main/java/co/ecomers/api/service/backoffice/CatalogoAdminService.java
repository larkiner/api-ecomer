package co.ecomers.api.service.backoffice;

import co.ecomers.api.dto.backoffice.CategoriaCreadaResponse;
import co.ecomers.api.dto.backoffice.CategoriaRequest;
import co.ecomers.api.dto.backoffice.CategoriaResponse;
import co.ecomers.api.dto.backoffice.ImpuestoResponse;
import co.ecomers.api.dto.backoffice.ProductoCreadoResponse;
import co.ecomers.api.dto.backoffice.ProductoEdicionRequest;
import co.ecomers.api.dto.backoffice.ProductoRequest;
import co.ecomers.api.dto.backoffice.ProductoResponse;
import co.ecomers.api.dto.backoffice.VarianteCreadaResponse;
import co.ecomers.api.dto.backoffice.VarianteEdicionRequest;
import co.ecomers.api.dto.backoffice.VarianteRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.CatalogoAdminRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Administración del catálogo: categorías, productos y variantes. Los permisos los verifica la base. */
@Service
public class CatalogoAdminService {

    static final int PAGINA_MAXIMA = 100_000;
    static final int TAMANO_MAXIMO = 50;

    private final CatalogoAdminRepository repositorio;
    private final ImagenService imagenes;

    public CatalogoAdminService(CatalogoAdminRepository repositorio, ImagenService imagenes) {
        this.repositorio = repositorio;
        this.imagenes = imagenes;
    }

    // ---------------- Lecturas ----------------

    public List<CategoriaResponse> listarCategorias(EmpleadoActual empleado) {
        return repositorio.listarCategorias(empleado);
    }

    public List<ImpuestoResponse> listarImpuestos(EmpleadoActual empleado) {
        return repositorio.listarImpuestos(empleado);
    }

    public List<ProductoResponse> listarProductos(EmpleadoActual empleado, int pagina, int tamano) {
        if (pagina < 0 || pagina > PAGINA_MAXIMA || tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new NegocioException(CodigoError.VALIDACION, "page debe estar entre 0 y " + PAGINA_MAXIMA
                    + " y size entre 1 y " + TAMANO_MAXIMO);
        }
        return repositorio.listarProductos(empleado, pagina, tamano);
    }

    // ---------------- Categorias ----------------

    /** La URL de la imagen se valida (debe ser de nuestro bucket) y se guarda ya normalizada. */
    public CategoriaCreadaResponse crearCategoria(EmpleadoActual empleado, CategoriaRequest req) {
        String imagen = imagenes.validarUrl(req.imagenUrl());
        return new CategoriaCreadaResponse(repositorio.crearCategoria(empleado, req.nombre().trim(), req.idPadre(), imagen));
    }

    public void actualizarCategoria(EmpleadoActual empleado, long idCategoria, CategoriaRequest req) {
        String imagen = imagenes.validarUrl(req.imagenUrl());
        repositorio.actualizarCategoria(empleado, idCategoria, req.nombre().trim(), req.idPadre(), imagen);
    }

    public void cambiarEstadoCategoria(EmpleadoActual empleado, long idCategoria, boolean activo) {
        repositorio.cambiarEstadoCategoria(empleado, idCategoria, activo);
    }

    // ---------------- Productos y variantes ----------------

    /** La URL de la imagen se valida (debe ser de nuestro bucket) y se guarda ya normalizada. */
    public ProductoCreadoResponse crearProducto(EmpleadoActual empleado, ProductoRequest req) {
        String imagen = imagenes.validarUrl(req.imagenUrl());
        return repositorio.crearProductoConVariantes(empleado, new ProductoRequest(req.idCategoria(), req.idImpuesto(),
                req.nombre(), req.descripcion(), imagen, req.variantes()));
    }

    public VarianteCreadaResponse agregarVariante(EmpleadoActual empleado, long idProducto, VarianteRequest req) {
        return new VarianteCreadaResponse(repositorio.agregarVariante(empleado, idProducto, req));
    }

    public void actualizarProducto(EmpleadoActual empleado, long idProducto, ProductoEdicionRequest req) {
        String imagen = imagenes.validarUrl(req.imagenUrl());
        repositorio.actualizarProducto(empleado, idProducto, new ProductoEdicionRequest(req.idCategoria(), req.idImpuesto(),
                req.nombre(), req.descripcion(), imagen));
    }

    public void cambiarEstadoProducto(EmpleadoActual empleado, long idProducto, boolean activo) {
        repositorio.cambiarEstadoProducto(empleado, idProducto, activo);
    }

    public void actualizarVariante(EmpleadoActual empleado, long idVariante, VarianteEdicionRequest req) {
        repositorio.actualizarVariante(empleado, idVariante, req);
    }

    public void cambiarEstadoVariante(EmpleadoActual empleado, long idVariante, boolean activo) {
        repositorio.cambiarEstadoVariante(empleado, idVariante, activo);
    }
}
