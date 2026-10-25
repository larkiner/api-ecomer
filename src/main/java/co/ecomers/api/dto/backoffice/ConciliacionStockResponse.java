package co.ecomers.api.dto.backoffice;

/** Diferencia entre STOCK y el kardex. Si el reporte sale vacio, todo cuadra. */
public record ConciliacionStockResponse(
        long idVariante,
        String sku,
        long totalSaldo,
        long totalKardex,
        long diferenciaTotal,
        long reservadaSaldo,
        long reservadaKardex,
        long diferenciaReservada) { }
