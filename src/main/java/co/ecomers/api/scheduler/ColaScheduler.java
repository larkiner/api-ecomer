package co.ecomers.api.scheduler;

import co.ecomers.api.service.integracion.CorreoService;
import co.ecomers.api.service.integracion.SincronizacionErpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara los consumidores de la cola de ECOMERS (correos y ERP). Se apaga con
 * app.cola.habilitada=false (por ejemplo, en pruebas o si otra instancia ya consume).
 * Varias instancias pueden consumir a la vez sin duplicar: la base usa FOR UPDATE SKIP LOCKED.
 */
@Component
@ConditionalOnProperty(name = "app.cola.habilitada", havingValue = "true", matchIfMissing = true)
public class ColaScheduler {

    private static final Logger log = LoggerFactory.getLogger(ColaScheduler.class);

    private final CorreoService correos;
    private final SincronizacionErpService erp;
    private final int tamanoLote;

    public ColaScheduler(CorreoService correos, SincronizacionErpService erp,
                         @Value("${app.cola.tamano-lote:10}") int tamanoLote) {
        this.correos = correos;
        this.erp = erp;
        this.tamanoLote = tamanoLote;
    }

    @Scheduled(fixedDelayString = "${app.cola.intervalo-ms:10000}", initialDelayString = "${app.cola.intervalo-ms:10000}")
    public void enviarCorreos() {
        try {
            correos.procesarPendientes(tamanoLote);
        } catch (RuntimeException e) {
            log.error("Fallo el ciclo de correos: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${app.cola.intervalo-ms:10000}", initialDelayString = "${app.cola.intervalo-ms:10000}")
    public void sincronizarErp() {
        try {
            erp.procesarPendientes(tamanoLote);
        } catch (RuntimeException e) {
            log.error("Fallo el ciclo de sincronizacion con el ERP: {}", e.toString());
        }
    }
}
