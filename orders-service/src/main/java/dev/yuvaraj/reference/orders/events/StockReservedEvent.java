package dev.yuvaraj.reference.orders.events;

/** Consumed from {@code inventory.stock-reserved}; closes the loop opened by OrderPlacedEvent. */
public record StockReservedEvent(
        String orderRef,
        boolean reserved,
        String reason) {
}
