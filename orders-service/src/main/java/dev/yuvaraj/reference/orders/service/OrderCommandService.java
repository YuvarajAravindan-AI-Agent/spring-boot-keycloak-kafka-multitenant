package dev.yuvaraj.reference.orders.service;

import java.time.Instant;
import java.util.UUID;

import dev.yuvaraj.reference.orders.api.dto.OrderView;
import dev.yuvaraj.reference.orders.api.dto.PlaceOrderRequest;
import dev.yuvaraj.reference.orders.client.InventoryClient;
import dev.yuvaraj.reference.orders.domain.OrderEntity;
import dev.yuvaraj.reference.orders.domain.OrderLine;
import dev.yuvaraj.reference.orders.events.OrderEventPublisher;
import dev.yuvaraj.reference.orders.events.OrderPlacedEvent;
import dev.yuvaraj.reference.orders.repo.OrderRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderCommandService {

    private static final Logger log = LoggerFactory.getLogger(OrderCommandService.class);

    private final OrderRepository orders;
    private final InventoryClient inventory;
    private final OrderEventPublisher publisher;

    public OrderCommandService(OrderRepository orders, InventoryClient inventory,
                               OrderEventPublisher publisher) {
        this.orders = orders;
        this.inventory = inventory;
        this.publisher = publisher;
    }

    @Transactional
    public OrderView place(PlaceOrderRequest request) {
        OrderEntity order = new OrderEntity(
                "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                request.customerName(),
                Instant.now());

        for (PlaceOrderRequest.Line line : request.lines()) {
            // Advisory check only. The binding reservation happens over Kafka; see InventoryClient.
            InventoryClient.StockLevel stock = inventory.getStock(line.sku());
            if (stock.available() < line.quantity()) {
                throw new InsufficientStockException(line.sku(), line.quantity(), stock.available());
            }
            order.addLine(new OrderLine(line.sku(), line.quantity(), line.unitPrice()));
        }

        OrderEntity saved = orders.save(order);
        publisher.publishOrderPlaced(new OrderPlacedEvent(
                saved.getOrderRef(),
                saved.getCustomerName(),
                saved.getLines().stream()
                        .map(l -> new OrderPlacedEvent.Line(l.getSku(), l.getQuantity()))
                        .toList()));

        log.info("orderRef={} placed with {} line(s), total={}",
                saved.getOrderRef(), saved.getLines().size(), saved.getTotalAmount());
        return OrderView.from(saved);
    }

    public static class InsufficientStockException extends RuntimeException {
        public InsufficientStockException(String sku, int requested, int available) {
            super("Insufficient stock for %s: requested %d, available %d"
                    .formatted(sku, requested, available));
        }
    }
}
