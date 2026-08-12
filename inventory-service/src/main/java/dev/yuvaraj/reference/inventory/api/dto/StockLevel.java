package dev.yuvaraj.reference.inventory.api.dto;

/** Cached in Redis, so it must stay serializable as plain JSON. */
public record StockLevel(String sku, int available) {
}
