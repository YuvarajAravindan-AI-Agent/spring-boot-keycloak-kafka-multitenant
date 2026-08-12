package dev.yuvaraj.reference.inventory.events;

/** Published to {@code inventory.stock-reserved} once every line of an order is decided. */
public record StockReservedEvent(
        String orderRef,
        boolean reserved,
        String reason) {
}
