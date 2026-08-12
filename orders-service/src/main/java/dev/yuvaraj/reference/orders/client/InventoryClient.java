package dev.yuvaraj.reference.orders.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Synchronous read of a stock level from inventory-service, used to reject obviously
 * unfulfillable orders before they are written.
 *
 * <p>This is a <em>check</em>, not a reservation. Between this call and the commit another
 * order can take the stock, which is exactly why the authoritative reservation happens
 * asynchronously over Kafka and can still come back REJECTED. Treating a synchronous read as
 * a lock is one of the more expensive mistakes in this style of architecture.
 */
@FeignClient(name = "inventory-service", url = "${platform.clients.inventory-url}")
public interface InventoryClient {

    @GetMapping("/api/inventory/{sku}")
    StockLevel getStock(@PathVariable("sku") String sku);

    record StockLevel(String sku, int available) {
    }
}
