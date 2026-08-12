package dev.yuvaraj.reference.orders.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import dev.yuvaraj.reference.orders.domain.OrderEntity;

/**
 * Read model for an order. Building one touches {@code order.getLines()}, which is what
 * makes the choice of fetch strategy observable rather than theoretical.
 */
public record OrderView(
        Long id,
        String orderRef,
        String customerName,
        Instant placedAt,
        String status,
        BigDecimal totalAmount,
        List<LineView> lines) {

    public static OrderView from(OrderEntity order) {
        return new OrderView(
                order.getId(),
                order.getOrderRef(),
                order.getCustomerName(),
                order.getPlacedAt(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getLines().stream().map(LineView::from).toList());
    }

    public record LineView(String sku, int quantity, BigDecimal unitPrice) {
        static LineView from(dev.yuvaraj.reference.orders.domain.OrderLine line) {
            return new LineView(line.getSku(), line.getQuantity(), line.getUnitPrice());
        }
    }
}
