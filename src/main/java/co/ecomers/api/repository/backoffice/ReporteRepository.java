package co.ecomers.api.repository.backoffice;

import co.ecomers.api.dto.backoffice.CarritoAbandonadoResponse;
import co.ecomers.api.dto.backoffice.ConciliacionPagoResponse;
import co.ecomers.api.dto.backoffice.ConciliacionStockResponse;
import co.ecomers.api.dto.backoffice.EventoColaResponse;
import co.ecomers.api.dto.backoffice.PedidoPendienteResponse;
import co.ecomers.api.dto.backoffice.StockBajoResponse;
import co.ecomers.api.dto.backoffice.TopClienteResponse;
import co.ecomers.api.dto.backoffice.VentaDiariaResponse;
import co.ecomers.api.dto.backoffice.VentaProductoResponse;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.oracle.BaseDatosEmpleado;
import co.ecomers.api.repository.oracle.Filas;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reportes del backoffice sobre las vistas de ECOMERS (solo lectura). Cada consulta lista sus
 * columnas y lleva un tope de filas: las vistas no tienen paginacion propia.
 */
@Repository
public class ReporteRepository {

    private final BaseDatosEmpleado db;

    public ReporteRepository(BaseDatosEmpleado db) {
        this.db = db;
    }

    public List<StockBajoResponse> stockBajo(EmpleadoActual e, int limite) {
        return lista(e, "SELECT ID_VARIANTE, SKU, NOMBRE_ITEM, CANTIDAD_TOTAL, CANTIDAD_RESERVADA, CANTIDAD_DISPONIBLE "
                        + "FROM ECOMERS.V_STOCK_BAJO ORDER BY CANTIDAD_DISPONIBLE, SKU FETCH FIRST :limite ROWS ONLY",
                limite, (rs, n) -> new StockBajoResponse(rs.getLong("ID_VARIANTE"), rs.getString("SKU"),
                        rs.getString("NOMBRE_ITEM"), rs.getLong("CANTIDAD_TOTAL"), rs.getLong("CANTIDAD_RESERVADA"),
                        rs.getLong("CANTIDAD_DISPONIBLE")));
    }

    public List<ConciliacionStockResponse> conciliacionStock(EmpleadoActual e, int limite) {
        return lista(e, "SELECT ID_VARIANTE, SKU, TOTAL_SALDO, TOTAL_KARDEX, DIF_TOTAL, RESERVADA_SALDO, RESERVADA_KARDEX, "
                        + "DIF_RESERVADA FROM ECOMERS.V_CONCILIACION_STOCK ORDER BY SKU FETCH FIRST :limite ROWS ONLY",
                limite, (rs, n) -> new ConciliacionStockResponse(rs.getLong("ID_VARIANTE"), rs.getString("SKU"),
                        rs.getLong("TOTAL_SALDO"), rs.getLong("TOTAL_KARDEX"), rs.getLong("DIF_TOTAL"),
                        rs.getLong("RESERVADA_SALDO"), rs.getLong("RESERVADA_KARDEX"), rs.getLong("DIF_RESERVADA")));
    }

    public List<PedidoPendienteResponse> pedidosPendientes(EmpleadoActual e, int limite) {
        return lista(e, "SELECT ID_PEDIDO, ID_PUBLICO, ID_CLIENTE, CLIENTE, EMAIL, TOTAL, FECHA_CREACION, "
                        + "FECHA_EXPIRACION, MINUTOS_RESTANTES FROM ECOMERS.V_PEDIDOS_PENDIENTES "
                        + "ORDER BY FECHA_EXPIRACION FETCH FIRST :limite ROWS ONLY",
                limite, (rs, n) -> new PedidoPendienteResponse(rs.getLong("ID_PEDIDO"), Filas.hex(rs, "ID_PUBLICO"),
                        rs.getLong("ID_CLIENTE"), rs.getString("CLIENTE"), rs.getString("EMAIL"), rs.getBigDecimal("TOTAL"),
                        Filas.fechaHora(rs, "FECHA_CREACION"), Filas.fechaHora(rs, "FECHA_EXPIRACION"),
                        Filas.longONulo(rs, "MINUTOS_RESTANTES")));
    }

    public List<VentaDiariaResponse> ventasDiarias(EmpleadoActual e, LocalDate desde, LocalDate hasta) {
        return db.lectura(e.usuarioBd(), s -> s.sql()
                .sql("SELECT FECHA, PEDIDOS_PAGADOS, SUBTOTAL, IMPUESTO, TOTAL FROM ECOMERS.V_VENTAS_DIARIAS "
                        + "WHERE FECHA BETWEEN :desde AND :hasta ORDER BY FECHA")
                .param("desde", desde).param("hasta", hasta)
                .query((rs, n) -> new VentaDiariaResponse(Filas.fecha(rs, "FECHA"), rs.getLong("PEDIDOS_PAGADOS"),
                        rs.getBigDecimal("SUBTOTAL"), rs.getBigDecimal("IMPUESTO"), rs.getBigDecimal("TOTAL")))
                .list());
    }

    public List<VentaProductoResponse> ventasProducto(EmpleadoActual e, LocalDate desde, LocalDate hasta, int limite) {
        return db.lectura(e.usuarioBd(), s -> s.sql()
                .sql("SELECT FECHA, ID_VARIANTE, SKU, NOMBRE_ACTUAL, UNIDADES, SUBTOTAL, IMPUESTO, TOTAL "
                        + "FROM ECOMERS.V_VENTAS_PRODUCTO WHERE FECHA BETWEEN :desde AND :hasta "
                        + "ORDER BY FECHA, TOTAL DESC FETCH FIRST :limite ROWS ONLY")
                .param("desde", desde).param("hasta", hasta).param("limite", limite)
                .query((rs, n) -> new VentaProductoResponse(Filas.fecha(rs, "FECHA"), rs.getLong("ID_VARIANTE"),
                        rs.getString("SKU"), rs.getString("NOMBRE_ACTUAL"), rs.getLong("UNIDADES"),
                        rs.getBigDecimal("SUBTOTAL"), rs.getBigDecimal("IMPUESTO"), rs.getBigDecimal("TOTAL")))
                .list());
    }

    public List<TopClienteResponse> topClientes(EmpleadoActual e, int limite) {
        return lista(e, "SELECT ID_CLIENTE, NOMBRE, EMAIL, PEDIDOS_PAGADOS, TOTAL_COMPRADO, ULTIMA_COMPRA "
                        + "FROM ECOMERS.V_TOP_CLIENTES ORDER BY TOTAL_COMPRADO DESC, ID_CLIENTE FETCH FIRST :limite ROWS ONLY",
                limite, (rs, n) -> new TopClienteResponse(rs.getLong("ID_CLIENTE"), rs.getString("NOMBRE"),
                        rs.getString("EMAIL"), rs.getLong("PEDIDOS_PAGADOS"), rs.getBigDecimal("TOTAL_COMPRADO"),
                        Filas.fechaHora(rs, "ULTIMA_COMPRA")));
    }

    public List<ConciliacionPagoResponse> conciliacionPagos(EmpleadoActual e, int limite) {
        return lista(e, "SELECT ID_PEDIDO, ID_PUBLICO_PEDIDO, ESTADO_PEDIDO, TOTAL_PEDIDO, ID_PAGO, MONTO_PAGO, "
                        + "FECHA_APROBACION, INCONSISTENCIA FROM ECOMERS.V_CONCILIACION_PAGOS "
                        + "ORDER BY ID_PEDIDO DESC FETCH FIRST :limite ROWS ONLY",
                limite, (rs, n) -> new ConciliacionPagoResponse(rs.getLong("ID_PEDIDO"), Filas.hex(rs, "ID_PUBLICO_PEDIDO"),
                        rs.getString("ESTADO_PEDIDO"), rs.getBigDecimal("TOTAL_PEDIDO"), Filas.longONulo(rs, "ID_PAGO"),
                        rs.getBigDecimal("MONTO_PAGO"), Filas.fechaHora(rs, "FECHA_APROBACION"),
                        rs.getString("INCONSISTENCIA")));
    }

    public List<CarritoAbandonadoResponse> carritosAbandonados(EmpleadoActual e, int limite) {
        return lista(e, "SELECT ID_CARRITO, ID_CLIENTE, NOMBRE, EMAIL, FECHA_ULTIMA_ACTIVIDAD, ITEMS "
                        + "FROM ECOMERS.V_CARRITOS_ABANDONADOS ORDER BY FECHA_ULTIMA_ACTIVIDAD FETCH FIRST :limite ROWS ONLY",
                limite, (rs, n) -> new CarritoAbandonadoResponse(rs.getLong("ID_CARRITO"), rs.getLong("ID_CLIENTE"),
                        rs.getString("NOMBRE"), rs.getString("EMAIL"), Filas.fechaHora(rs, "FECHA_ULTIMA_ACTIVIDAD"),
                        rs.getInt("ITEMS")));
    }

    public List<EventoColaResponse> colaPendiente(EmpleadoActual e, int limite) {
        return lista(e, "SELECT ID_COLA_EVENTO, TIPO_EVENTO, CLAVE_IDEMPOTENCIA, ESTADO, INTENTOS, PROXIMO_INTENTO, "
                        + "FECHA_BLOQUEO, ULTIMO_ERROR, FECHA_CREACION, ATASCADO FROM ECOMERS.V_COLA_PENDIENTE "
                        + "ORDER BY ATASCADO DESC, FECHA_CREACION FETCH FIRST :limite ROWS ONLY",
                limite, (rs, n) -> new EventoColaResponse(rs.getLong("ID_COLA_EVENTO"), rs.getString("TIPO_EVENTO"),
                        rs.getString("CLAVE_IDEMPOTENCIA"), rs.getString("ESTADO"), rs.getInt("INTENTOS"),
                        Filas.fechaHora(rs, "PROXIMO_INTENTO"), Filas.fechaHora(rs, "FECHA_BLOQUEO"),
                        rs.getString("ULTIMO_ERROR"), Filas.fechaHora(rs, "FECHA_CREACION"), rs.getInt("ATASCADO") == 1));
    }

    private <T> List<T> lista(EmpleadoActual e, String sql, int limite, RowMapper<T> mapeador) {
        return db.lectura(e.usuarioBd(), s -> s.sql().sql(sql).param("limite", limite).query(mapeador).list());
    }
}
