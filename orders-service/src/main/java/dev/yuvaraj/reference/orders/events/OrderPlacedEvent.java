package dev.yuvaraj.reference.orders.events;

import java.util.List;

/**
 * Published to {@code orders.placed} when an order is accepted.
 *
 * <p>Event contracts are duplicated in each service rather than shared through a common jar.
 * A shared model turns every consumer into a compile-time dependency of the producer, which
 * is the thing microservices are meant to avoid: you end up redeploying four services to add
 * one optional field. The cost is a hand-maintained copy on each side; the benefit is that
 * consumers upgrade on their own schedule.
 *
 * <p>The tenant travels as a Kafka <em>header</em>, not a field, so infrastructure concerns
 * (routing, filtering, audit) can read it without deserialising the payload.
 */
public record OrderPlacedEvent(
        String orderRef,
        String customerName,
        List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
