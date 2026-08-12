package dev.yuvaraj.reference.inventory.events;

import java.nio.charset.StandardCharsets;

import dev.yuvaraj.reference.inventory.service.InventoryService;
import dev.yuvaraj.reference.security.TenantContext;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Reserves stock for a placed order and reports the outcome back.
 *
 * <p>All-or-nothing per order: if any line cannot be satisfied the whole order is rejected.
 * Lines already taken by that point are <em>not</em> rolled back here, which is a deliberate
 * simplification with a real consequence — a rejected order can still have consumed stock.
 * A production system would either reserve inside one transaction across all lines, or run a
 * compensating release. That is the choice a saga exists to make explicit, and pretending it
 * away is how inventory drifts.
 */
@Component
public class OrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final InventoryService inventory;
    private final KafkaTemplate<String, Object> kafka;
    private final String outboundTopic;

    public OrderPlacedListener(
            InventoryService inventory,
            KafkaTemplate<String, Object> kafka,
            @Value("${platform.kafka.topics.stock-reserved:inventory.stock-reserved}") String outboundTopic) {
        this.inventory = inventory;
        this.kafka = kafka;
        this.outboundTopic = outboundTopic;
    }

    @KafkaListener(
            topics = "${platform.kafka.topics.order-placed:orders.placed}",
            groupId = "${spring.kafka.consumer.group-id:inventory-service}")
    public void onOrderPlaced(
            @Payload OrderPlacedEvent event,
            @Header(name = TENANT_HEADER, required = false) byte[] tenantHeader) {

        if (tenantHeader == null) {
            log.error("orderRef={} dropped: no {} header", event.orderRef(), TENANT_HEADER);
            return;
        }
        String tenant = new String(tenantHeader, StandardCharsets.UTF_8);

        try {
            TenantContext.set(tenant);
            boolean allReserved = true;
            String reason = "all lines reserved";

            for (OrderPlacedEvent.Line line : event.lines()) {
                if (!inventory.reserve(line.sku(), line.quantity())) {
                    allReserved = false;
                    reason = "insufficient stock for " + line.sku();
                    break;
                }
            }

            publish(tenant, new StockReservedEvent(event.orderRef(), allReserved, reason));
            log.info("tenant={} orderRef={} reserved={} ({})",
                    tenant, event.orderRef(), allReserved, reason);
        } finally {
            TenantContext.clear();
        }
    }

    private void publish(String tenant, StockReservedEvent event) {
        ProducerRecord<String, Object> record =
                new ProducerRecord<>(outboundTopic, event.orderRef(), event);
        record.headers().add(new RecordHeader(
                TENANT_HEADER, tenant.getBytes(StandardCharsets.UTF_8)));
        kafka.send(record);
    }
}
