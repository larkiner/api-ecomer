package co.ecomers.api.dto.backoffice;

public record StockBajoResponse(long idVariante, String sku, String nombre, long total, long reservada, long disponible) { }
