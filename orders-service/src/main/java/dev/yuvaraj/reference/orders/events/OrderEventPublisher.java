package dev.yuvaraj.reference.orders.events;

import java.nio.charset.StandardCharsets;

import dev.yuvaraj.reference.security.TenantContext;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes order events, keyed by order reference and stamped with the tenant.
 *
 * <p>Publication is deferred to {@code afterCommit}. Sending inside the transaction is the
 * common shape and it is wrong in a way that only shows up under load: the consumer can
 * receive the event, call back for the order, and get nothing, because the producing
 * transaction has not committed yet. Deferring trades that race for an acknowledged
 * at-most-once gap, which for this flow is the better failure &mdash; and is the point at
 * which a real system would reach for the transactional outbox pattern instead.
 *
 * <p>The key is {@code orderRef}, so all events for one order land on the same partition and
 * stay ordered relative to each other.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);
    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final KafkaTemplate<String, Object> kafka;
    private final String topic;

    public OrderEventPublisher(KafkaTemplate<String, Object> kafka,
                               @Value("${platform.kafka.topics.order-placed:orders.placed}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    public void publishOrderPlaced(OrderPlacedEvent event) {
        String tenant = TenantContext.requireTenant();
        Runnable send = () -> {
            ProducerRecord<String, Object> record =
                    new ProducerRecord<>(topic, event.orderRef(), event);
            record.headers().add(new RecordHeader(
                    TENANT_HEADER, tenant.getBytes(StandardCharsets.UTF_8)));
            kafka.send(record).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.error("tenant={} orderRef={} failed to publish to {}",
                            tenant, event.orderRef(), topic, failure);
                } else {
                    log.info("tenant={} orderRef={} published to {}-{}@{}",
                            tenant, event.orderRef(), topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                }
            });
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }
}
