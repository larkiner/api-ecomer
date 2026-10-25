package co.ecomers.api.repository.backoffice;

import co.ecomers.api.dto.backoffice.CategoriaResponse;
import co.ecomers.api.dto.backoffice.ImpuestoResponse;
import co.ecomers.api.dto.backoffice.ProductoCreadoResponse;
import co.ecomers.api.dto.backoffice.ProductoEdicionRequest;
import co.ecomers.api.dto.backoffice.ProductoRequest;
import co.ecomers.api.dto.backoffice.ProductoResponse;
import co.ecomers.api.dto.backoffice.VarianteEdicionRequest;
import co.ecomers.api.dto.backoffice.VarianteRequest;
import co.ecomers.api.dto.backoffice.VarianteResponse;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import co.ecomers.api.repository.oracle.Filas;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Categorías, productos y variantes por PKG_CATALOGO. El rol empleado no puede leer ni escribir tablas: todo pasa por el
 * paquete, que además verifica el permiso del empleado con el ID que recibe primero.
 */
@Repository
public class CatalogoAdminRepository {

    private static final String PKG = "ECOMERS.PKG_CATALOGO.";

    private final BaseDatosEmpleado db;

    public CatalogoAdminRepository(BaseDatosEmpleado db) {
        this.db = db;
    }

    // ---------------- Lecturas (cualquier empleado activo) ----------------

    public List<CategoriaResponse> listarCategorias(EmpleadoActual e) {
        return db.lectura(e.usuarioBd(), s -> s.funcionCursor(PKG + "listar_categorias",
                (rs, n) -> new CategoriaResponse(rs.getLong("ID_CATEGORIA"), Filas.longONulo(rs, "ID_CATEGORIA_PADRE"),
                        rs.getString("NOMBRE"), rs.getInt("ACTIVO") == 1, rs.getString("IMAGEN_URL")),
                e.idEmpleado()));
    }

    public List<ImpuestoResponse> listarImpuestos(EmpleadoActual e) {
        return db.lectura(e.usuarioBd(), s -> s.funcionCursor(PKG + "listar_impuestos",
                (rs, n) -> new ImpuestoResponse(rs.getLong("ID_IMPUESTO"), rs.getString("NOMBRE"),
                        rs.getBigDecimal("TASA"), rs.getInt("ACTIVO") == 1),
                e.idEmpleado()));
    }

    /** Una fila del cursor: los datos del producto y, si tiene, una de sus variantes. */
    private record FilaProducto(long idProducto, long idCategoria, long idImpuesto, String nombre, String descripcion,
                                String imagenUrl, boolean activo, VarianteResponse variante) { }

    /** Productos de una página (la base pagina por producto), con todas sus variantes, inactivas incluidas. */
    public List<ProductoResponse> listarProductos(EmpleadoActual e, int pagina, int tamano) {
        List<FilaProducto> filas = db.lectura(e.usuarioBd(), s -> s.funcionCursor(PKG + "listar_productos",
                (rs, n) -> {
                    Long idVariante = Filas.longONulo(rs, "ID_VARIANTE");
                    VarianteResponse variante = idVariante == null ? null : new VarianteResponse(idVariante,
                            rs.getString("SKU"), rs.getString("VARIANTE"), rs.getBigDecimal("PRECIO_BASE"),
                            rs.getInt("CONTROLA_STOCK") == 1, rs.getInt("VARIANTE_ACTIVA") == 1);
                    return new FilaProducto(rs.getLong("ID_PRODUCTO"), rs.getLong("ID_CATEGORIA"),
                            rs.getLong("ID_IMPUESTO"), rs.getString("PRODUCTO"), rs.getString("DESCRIPCION"),
                            rs.getString("IMAGEN_URL"), rs.getInt("PRODUCTO_ACTIVO") == 1, variante);
                },
                e.idEmpleado(), pagina, tamano));

        Map<Long, FilaProducto> cabeceras = new LinkedHashMap<>();
        Map<Long, List<VarianteResponse>> variantes = new HashMap<>();
        for (FilaProducto f : filas) {
            cabeceras.putIfAbsent(f.idProducto(), f);
            List<VarianteResponse> deEste = variantes.computeIfAbsent(f.idProducto(), id -> new ArrayList<>());
            if (f.variante() != null) {
                deEste.add(f.variante());
            }
        }
        return cabeceras.values().stream()
                .map(c -> new ProductoResponse(c.idProducto(), c.idCategoria(), c.idImpuesto(), c.nombre(),
                        c.descripcion(), c.imagenUrl(), c.activo(), List.copyOf(variantes.get(c.idProducto()))))
                .toList();
    }

    // ---------------- Categorias (permiso CATEGORIAS) ----------------

    /** Crea la categoría y, si trae imagen, la fija en la MISMA transacción: si algo falla no queda nada a medias. */
    public long crearCategoria(EmpleadoActual e, String nombre, Long idPadre, String imagenUrl) {
        return db.enTransaccion(e.usuarioBd(), s -> {
            long idCategoria = s.funcionNumero(PKG + "crear_categoria", e.idEmpleado(), nombre, idPadre);
            if (imagenUrl != null) {
                s.procedimiento(PKG + "fijar_imagen_categoria", e.idEmpleado(), idCategoria, imagenUrl);
            }
            return idCategoria;
        });
    }

    public void actualizarCategoria(EmpleadoActual e, long idCategoria, String nombre, Long idPadre, String imagenUrl) {
        // La imagen se fija en la MISMA transaccion: PUT reemplaza la categoria entera, asi que sin imagenUrl se quita.
        db.enTransaccionSinResultado(e.usuarioBd(), s -> {
            s.procedimiento(PKG + "actualizar_categoria", e.idEmpleado(), idCategoria, nombre, idPadre);
            s.procedimiento(PKG + "fijar_imagen_categoria", e.idEmpleado(), idCategoria, imagenUrl);
        });
    }

    public void cambiarEstadoCategoria(EmpleadoActual e, long idCategoria, boolean activo) {
        db.enTransaccionSinResultado(e.usuarioBd(), s -> s.procedimiento(PKG + "cambiar_estado_categoria",
                e.idEmpleado(), idCategoria, activo ? 1 : 0));
    }

    // ---------------- Productos y variantes (permiso PRODUCTOS) ----------------

    /**
     * Crea el producto y todas sus variantes en UNA transacción: si una variante falla (por ejemplo, un SKU repetido), no
     * queda ni el producto ni las demás variantes.
     */
    public ProductoCreadoResponse crearProductoConVariantes(EmpleadoActual e, ProductoRequest req) {
        return db.enTransaccion(e.usuarioBd(), s -> {
            long idProducto = s.funcionNumero(PKG + "crear_producto", e.idEmpleado(), req.idCategoria(),
                    req.idImpuesto(), req.nombre(), req.descripcion());
            if (req.imagenUrl() != null) {
                s.procedimiento(PKG + "fijar_imagen", e.idEmpleado(), idProducto, req.imagenUrl());
            }
            List<Long> idsVariantes = new ArrayList<>();
            for (VarianteRequest v : req.variantes()) {
                idsVariantes.add(s.funcionNumero(PKG + "agregar_variante", e.idEmpleado(), idProducto, v.sku(),
                        v.nombre(), v.precioBase(), v.controlaStock() ? 1 : 0));
            }
            return new ProductoCreadoResponse(idProducto, List.copyOf(idsVariantes));
        });
    }

    public long agregarVariante(EmpleadoActual e, long idProducto, VarianteRequest v) {
        return db.enTransaccion(e.usuarioBd(), s -> s.funcionNumero(PKG + "agregar_variante", e.idEmpleado(),
                idProducto, v.sku(), v.nombre(), v.precioBase(), v.controlaStock() ? 1 : 0));
    }

    public void actualizarProducto(EmpleadoActual e, long idProducto, ProductoEdicionRequest req) {
        // La imagen se fija en la MISMA transaccion: PUT reemplaza el producto entero, asi que sin imagenUrl se quita.
        db.enTransaccionSinResultado(e.usuarioBd(), s -> {
            s.procedimiento(PKG + "actualizar_producto", e.idEmpleado(), idProducto, req.idCategoria(),
                    req.idImpuesto(), req.nombre(), req.descripcion());
            s.procedimiento(PKG + "fijar_imagen", e.idEmpleado(), idProducto, req.imagenUrl());
        });
    }

    public void cambiarEstadoProducto(EmpleadoActual e, long idProducto, boolean activo) {
        db.enTransaccionSinResultado(e.usuarioBd(), s -> s.procedimiento(PKG + "cambiar_estado_producto",
                e.idEmpleado(), idProducto, activo ? 1 : 0));
    }

    public void actualizarVariante(EmpleadoActual e, long idVariante, VarianteEdicionRequest req) {
        db.enTransaccionSinResultado(e.usuarioBd(), s -> s.procedimiento(PKG + "actualizar_variante", e.idEmpleado(),
                idVariante, req.nombre(), req.precioBase()));
    }

    public void cambiarEstadoVariante(EmpleadoActual e, long idVariante, boolean activo) {
        db.enTransaccionSinResultado(e.usuarioBd(), s -> s.procedimiento(PKG + "cambiar_estado_variante",
                e.idEmpleado(), idVariante, activo ? 1 : 0));
    }
}
