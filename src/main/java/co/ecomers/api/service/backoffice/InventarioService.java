package co.ecomers.api.service.backoffice;

import co.ecomers.api.dto.backoffice.AjusteInventarioRequest;
import co.ecomers.api.dto.backoffice.EntradaInventarioRequest;
import co.ecomers.api.exception.CodigoError;
import co.ecomers.api.exception.NegocioException;
import co.ecomers.api.model.EmpleadoActual;
import co.ecomers.api.repository.backoffice.InventarioRepository;
import org.springframework.stereotype.Service;

/**
 * Movimientos de inventario. PKG_INVENTARIO actualiza STOCK y el kardex en una sola operacion y
 * no deja el total por debajo de lo reservado; aqui se validan las reglas de entrada antes de
 * tocar la base.
 */
@Service
public class InventarioService {

    private final InventarioRepository repo;

    public InventarioService(InventarioRepository repo) {
        this.repo = repo;
    }

    public void registrarEntrada(EmpleadoActual empleado, long idVariante, EntradaInventarioRequest req) {
        repo.entrada(empleado, validarVariante(idVariante), req.cantidad(), textoONulo(req.observacion()));
    }

    public void registrarAjuste(EmpleadoActual empleado, long idVariante, AjusteInventarioRequest req) {
        validarVariante(idVariante);
        if (req.cantidad() == 0) {
            throw new NegocioException(CodigoError.VALIDACION, "Un ajuste no puede ser de cero unidades");
        }
        String observacion = textoONulo(req.observacion());
        if (observacion == null) {
            throw new NegocioException(CodigoError.OBSERVACION_REQUERIDA, "El ajuste necesita una observación");
        }
        repo.ajuste(empleado, idVariante, req.cantidad(), observacion);
    }

    private static long validarVariante(long idVariante) {
        if (idVariante <= 0) {
            throw new NegocioException(CodigoError.VALIDACION, "El id de la variante debe ser positivo");
        }
        return idVariante;
    }

    private static String textoONulo(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
