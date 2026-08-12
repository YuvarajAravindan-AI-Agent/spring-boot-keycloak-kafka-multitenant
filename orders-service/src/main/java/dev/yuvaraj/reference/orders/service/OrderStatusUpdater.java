package dev.yuvaraj.reference.orders.service;

import dev.yuvaraj.reference.orders.domain.OrderEntity;
import dev.yuvaraj.reference.orders.domain.OrderStatus;
import dev.yuvaraj.reference.orders.repo.OrderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies an inventory decision to an order.
 *
 * <p>Deliberately a separate bean from the Kafka listener. {@code @Transactional} is applied
 * by a proxy, so a listener calling its own annotated method would run with no transaction at
 * all &mdash; the annotation reads as if it works and silently does nothing. Crossing a bean
 * boundary is what actually starts the transaction.
 */
@Service
public class OrderStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusUpdater.class);

    private final OrderRepository orders;

    public OrderStatusUpdater(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * @return {@code true} if an order was found for the bound tenant and updated
     */
    @Transactional
    public boolean applyReservationOutcome(String orderRef, boolean reserved, String reason) {
        OrderEntity order = orders.findByOrderRef(orderRef);
        if (order == null) {
            return false;
        }
        order.markStatus(reserved ? OrderStatus.RESERVED : OrderStatus.REJECTED);
        orders.save(order);
        log.info("orderRef={} -> {} ({})", orderRef, order.getStatus(), reason);
        return true;
    }
}
