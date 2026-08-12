package dev.yuvaraj.reference.inventory.events;

import java.util.List;

/**
 * Consumer-side copy of the orders contract.
 *
 * <p>Intentionally a separate declaration from the producer's record rather than a shared
 * jar &mdash; see the note on the orders-service copy. This side deliberately omits fields it
 * does not use ({@code customerName}); Jackson ignores unknown properties, so the producer can
 * add fields without this service recompiling.
 */
public record OrderPlacedEvent(
        String orderRef,
        List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
