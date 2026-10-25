package co.ecomers.api.repository.oracle;

import java.util.Set;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reintenta toda la transaccion ante los errores transitorios de la base (carrito, pedido o
 * pago ocupados por otra operacion): 3 intentos en total, con esperas de 100 y 300 ms.
 */
@Component
public class ReintentoOracle {

    static final Set<Integer> TRANSITORIOS = Set.of(20144, 20168, 20188);
    static final long[] ESPERAS_MS = {100, 300};

    private final LongConsumer espera;

    @Autowired
    public ReintentoOracle() {
        this(ms -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    ReintentoOracle(LongConsumer espera) {
        this.espera = espera;
    }

    public <T> T ejecutar(Supplier<T> intento) {
        for (int i = 0; ; i++) {
            try {
                return intento.get();
            } catch (RuntimeException e) {
                boolean transitorio = TraductorErroresOracle.codigoOracle(e).map(TRANSITORIOS::contains).orElse(false);
                if (!transitorio || i >= ESPERAS_MS.length) {
                    throw e;
                }
                espera.accept(ESPERAS_MS[i]);
            }
        }
    }
}
