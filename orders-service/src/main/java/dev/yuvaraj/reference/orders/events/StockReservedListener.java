package dev.yuvaraj.reference.orders.events;

import java.nio.charset.StandardCharsets;

import dev.yuvaraj.reference.orders.service.OrderStatusUpdater;
import dev.yuvaraj.reference.security.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Moves an order to RESERVED or REJECTED when inventory reports back.
 *
 * <p>A Kafka listener runs on a container thread with no request behind it, so nothing has
 * populated {@link TenantContext}. Without the explicit bind below, Hibernate's tenant
 * resolver returns the unbound sentinel and the lookup silently matches nothing &mdash; the
 * update appears to succeed and the order sits in PLACED forever. Binding the tenant from the
 * record header is what makes the write land in the right partition of data.
 *
 * <p>The bind is undone in a {@code finally}: listener threads are pooled and reused across
 * tenants, so a leaked value here is a cross-tenant write on the next record.
 */
@Component
public class StockReservedListener {

    private static final Logger log = LoggerFactory.getLogger(StockReservedListener.class);

    private final OrderStatusUpdater statusUpdater;

    public StockReservedListener(OrderStatusUpdater statusUpdater) {
        this.statusUpdater = statusUpdater;
    }

    @KafkaListener(
            topics = "${platform.kafka.topics.stock-reserved:inventory.stock-reserved}",
            groupId = "${spring.kafka.consumer.group-id:orders-service}")
    public void onStockReserved(
            @Payload StockReservedEvent event,
            @Header(name = OrderEventPublisher.TENANT_HEADER, required = false) byte[] tenantHeader) {

        if (tenantHeader == null) {
            log.error("orderRef={} dropped: no {} header on the record",
                    event.orderRef(), OrderEventPublisher.TENANT_HEADER);
            return;
        }
        String tenant = new String(tenantHeader, StandardCharsets.UTF_8);
        try {
            TenantContext.set(tenant);
            boolean applied = statusUpdater.applyReservationOutcome(
                    event.orderRef(), event.reserved(), event.reason());
            if (!applied) {
                log.warn("tenant={} orderRef={} not found for this tenant; ignoring",
                        tenant, event.orderRef());
            }
        } finally {
            TenantContext.clear();
        }
    }
}
