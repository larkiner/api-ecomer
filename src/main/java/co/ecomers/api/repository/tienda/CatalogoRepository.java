package co.ecomers.api.repository.tienda;

import co.ecomers.api.dto.tienda.CategoriaPublicaResponse;
import co.ecomers.api.dto.tienda.VarianteResponse;
import co.ecomers.api.repository.oracle.BaseDatosCliente;
import co.ecomers.api.repository.oracle.Filas;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Catalogo vendible (V_CATALOGO = V_PRECIO_VIGENTE + imagen y categoria del producto) y categorias activas
 * (V_CATEGORIA_PUBLICA): lo unico que el rol cliente puede leer. Solo lectura.
 */
@Repository
public class CatalogoRepository {

    private static final String USUARIO = "catalogo";
    private static final String COLUMNAS = "ID_VARIANTE, SKU, NOMBRE_ITEM, CONTROLA_STOCK, PRECIO_BASE, "
            + "ID_PROMOCION, DESCUENTO_UNITARIO, PRECIO_FINAL, TASA_IMPUESTO, IMAGEN_URL";
    private static final String FILTRO_TEXTO = "(UPPER(NOMBRE_ITEM) LIKE :patron ESCAPE '\\' OR SKU LIKE :patron ESCAPE '\\')";
    /** La categoria elegida y todas sus subcategorias activas (la tienda no lee la tabla CATEGORIA, solo la vista). */
    private static final String FILTRO_CATEGORIA = "ID_CATEGORIA IN (SELECT ID_CATEGORIA FROM ECOMERS.V_CATEGORIA_PUBLICA "
            + "START WITH ID_CATEGORIA = :categoria CONNECT BY PRIOR ID_CATEGORIA = ID_PADRE)";

    private final BaseDatosCliente db;

    public CatalogoRepository(BaseDatosCliente db) {
        this.db = db;
    }

    /**
     * @param busqueda  texto ya normalizado por el servicio, o null para no filtrar
     * @param categoria ID de una categoria (se incluyen sus subcategorias), o null para no filtrar
     */
    public long contar(String busqueda, Long categoria) {
        return db.lectura(USUARIO, s -> {
            var consulta = s.sql().sql("SELECT COUNT(*) FROM ECOMERS.V_CATALOGO" + donde(busqueda, categoria));
            if (busqueda != null) {
                consulta = consulta.param("patron", patron(busqueda));
            }
            if (categoria != null) {
                consulta = consulta.param("categoria", categoria);
            }
            return consulta.query(Long.class).single();
        });
    }

    public List<VarianteResponse> listar(String busqueda, Long categoria, int offset, int limite) {
        return db.lectura(USUARIO, s -> {
            var consulta = s.sql().sql("SELECT " + COLUMNAS + " FROM ECOMERS.V_CATALOGO" + donde(busqueda, categoria)
                    + " ORDER BY NOMBRE_ITEM, ID_VARIANTE OFFSET :offset ROWS FETCH NEXT :limite ROWS ONLY");
            if (busqueda != null) {
                consulta = consulta.param("patron", patron(busqueda));
            }
            if (categoria != null) {
                consulta = consulta.param("categoria", categoria);
            }
            return consulta.param("offset", offset).param("limite", limite).query(CatalogoRepository::mapear).list();
        });
    }

    /** Categorias activas con su imagen, para mostrarlas y filtrar el catalogo. */
    public List<CategoriaPublicaResponse> listarCategorias() {
        return db.lectura(USUARIO, s -> s.sql()
                .sql("SELECT ID_CATEGORIA, ID_PADRE, NOMBRE, IMAGEN_URL FROM ECOMERS.V_CATEGORIA_PUBLICA "
                        + "ORDER BY NOMBRE, ID_CATEGORIA")
                .query((rs, fila) -> new CategoriaPublicaResponse(rs.getLong("ID_CATEGORIA"),
                        Filas.longONulo(rs, "ID_PADRE"), rs.getString("NOMBRE"), rs.getString("IMAGEN_URL")))
                .list());
    }

    /** Solo se concatenan fragmentos fijos de este archivo: los valores del usuario siempre van como parametros. */
    static String donde(String busqueda, Long categoria) {
        if (busqueda == null && categoria == null) {
            return "";
        }
        if (busqueda != null && categoria != null) {
            return " WHERE " + FILTRO_TEXTO + " AND " + FILTRO_CATEGORIA;
        }
        return " WHERE " + (busqueda != null ? FILTRO_TEXTO : FILTRO_CATEGORIA);
    }

    public Optional<VarianteResponse> buscar(long idVariante) {
        return db.lectura(USUARIO, s -> s.sql()
                .sql("SELECT " + COLUMNAS + " FROM ECOMERS.V_CATALOGO WHERE ID_VARIANTE = :id")
                .param("id", idVariante)
                .query(CatalogoRepository::mapear)
                .optional());
    }

    /** Patron LIKE en mayusculas, escapando % _ y la barra invertida (la busqueda nunca se concatena al SQL). */
    static String patron(String busqueda) {
        String limpio = busqueda.trim().toUpperCase()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + limpio + "%";
    }

    private static VarianteResponse mapear(ResultSet rs, int fila) throws SQLException {
        return new VarianteResponse(
                rs.getLong("ID_VARIANTE"),
                rs.getString("SKU"),
                rs.getString("NOMBRE_ITEM"),
                rs.getInt("CONTROLA_STOCK") == 1,
                rs.getBigDecimal("PRECIO_BASE"),
                rs.getBigDecimal("DESCUENTO_UNITARIO"),
                rs.getBigDecimal("PRECIO_FINAL"),
                rs.getBigDecimal("TASA_IMPUESTO"),
                Filas.longONulo(rs, "ID_PROMOCION"),
                rs.getString("IMAGEN_URL"));
    }
}
