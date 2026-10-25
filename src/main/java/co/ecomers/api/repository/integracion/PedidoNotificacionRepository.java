package co.ecomers.api.repository.integracion;

import co.ecomers.api.model.PedidoParaNotificar;
import co.ecomers.api.repository.oracle.BaseDatosIntegracion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Lectura de PEDIDO y PEDIDO_ITEM (SELECT otorgado al rol de integracion) para el correo y el ERP. */
@Repository
public class PedidoNotificacionRepository {

    private static final String USUARIO = "cola:lectura";

    private final BaseDatosIntegracion db;

    public PedidoNotificacionRepository(BaseDatosIntegracion db) {
        this.db = db;
    }

    public Optional<PedidoParaNotificar> buscar(long idPedido) {
        return db.lectura(USUARIO, s -> {
            Optional<Cabecera> cabecera = s.sql()
                    .sql("SELECT FACT_NOMBRE, FACT_EMAIL, SUBTOTAL, IMPUESTO_TOTAL, TOTAL FROM ECOMERS.PEDIDO WHERE ID_PEDIDO = :id")
                    .param("id", idPedido)
                    .query((rs, n) -> new Cabecera(rs.getString("FACT_NOMBRE"), rs.getString("FACT_EMAIL"),
                            rs.getBigDecimal("SUBTOTAL"), rs.getBigDecimal("IMPUESTO_TOTAL"), rs.getBigDecimal("TOTAL")))
                    .optional();
            if (cabecera.isEmpty()) {
                return Optional.<PedidoParaNotificar>empty();
            }
            List<PedidoParaNotificar.Linea> lineas = s.sql()
                    .sql("SELECT SKU, NOMBRE_ITEM, CANTIDAD, TOTAL_LINEA FROM ECOMERS.PEDIDO_ITEM "
                            + "WHERE ID_PEDIDO = :id ORDER BY ID_PEDIDO_ITEM")
                    .param("id", idPedido)
                    .query((rs, n) -> new PedidoParaNotificar.Linea(rs.getString("SKU"), rs.getString("NOMBRE_ITEM"),
                            rs.getInt("CANTIDAD"), rs.getBigDecimal("TOTAL_LINEA")))
                    .list();
            Cabecera c = cabecera.get();
            return Optional.of(new PedidoParaNotificar(idPedido, c.nombre(), c.email(), c.subtotal(), c.impuesto(),
                    c.total(), lineas));
        });
    }

    private record Cabecera(String nombre, String email, BigDecimal subtotal, BigDecimal impuesto, BigDecimal total) { }
}
