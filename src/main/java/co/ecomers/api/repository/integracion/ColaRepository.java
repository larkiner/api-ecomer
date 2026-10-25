package co.ecomers.api.repository.integracion;

import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EventoCola;
import co.ecomers.api.repository.oracle.BaseDatosIntegracion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** Consumo de COLA_EVENTO a traves de PKG_COLA. Cada operacion es su propia transaccion. */
@Repository
public class ColaRepository {

    private static final Logger log = LoggerFactory.getLogger(ColaRepository.class);
    private static final String PKG = "ECOMERS.PKG_COLA.";
    private static final int MAX_ERROR = 4000;

    private final BaseDatosIntegracion db;

    public ColaRepository(BaseDatosIntegracion db) {
        this.db = db;
    }

    /** Toma hasta {@code limite} eventos y los deja PROCESANDO; el COMMIT al cerrar la transaccion suelta los bloqueos. */
    public List<EventoCola> tomarLote(String tipo, int limite) {
        return db.enTransaccion(usuario(tipo),
                s -> s.funcionCursor(PKG + "tomar_lote", ColaRepository::mapear, tipo, limite));
    }

    public void completar(long idEvento, String tipo) {
        ignorandoYaResuelto(idEvento, () -> db.enTransaccionSinResultado(usuario(tipo),
                s -> s.procedimiento(PKG + "completar", idEvento)));
    }

    /** Para SINCRONIZAR_ERP: guarda PEDIDO_ERP y completa el evento en una sola transaccion. */
    public void completarErp(long idEvento, long idPedido, String idErp) {
        ignorandoYaResuelto(idEvento, () -> db.enTransaccionSinResultado(usuario(EventoCola.SINCRONIZAR_ERP),
                s -> s.procedimiento(PKG + "completar_erp", idEvento, idPedido, idErp)));
    }

    public void fallar(long idEvento, String tipo, String error) {
        String texto = error == null ? "" : error.length() > MAX_ERROR ? error.substring(0, MAX_ERROR) : error;
        ignorandoYaResuelto(idEvento, () -> db.enTransaccionSinResultado(usuario(tipo),
                s -> s.procedimiento(PKG + "fallar", idEvento, texto)));
    }

    /** ORA-20204: otro consumidor lo tomo o ya se completo. No es un error. */
    private static void ignorandoYaResuelto(long idEvento, Runnable accion) {
        try {
            accion.run();
        } catch (NegocioException e) {
            if (e.codigo() != CodigoError.EVENTO_NO_PROCESANDO) {
                throw e;
            }
            log.info("El evento {} ya no estaba PROCESANDO; se ignora", idEvento);
        }
    }

    private static String usuario(String tipo) {
        return "cola:" + tipo;
    }

    private static EventoCola mapear(ResultSet rs, int fila) throws SQLException {
        return new EventoCola(rs.getLong("ID_COLA_EVENTO"), rs.getString("TIPO_EVENTO"),
                rs.getString("CLAVE_IDEMPOTENCIA"), rs.getString("PAYLOAD"), rs.getInt("INTENTOS"));
    }
}
