package dev.yuvaraj.reference.orders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import dev.yuvaraj.reference.orders.domain.OrderEntity;
import dev.yuvaraj.reference.orders.domain.OrderLine;
import dev.yuvaraj.reference.orders.repo.OrderRepository;
import dev.yuvaraj.reference.security.TenantContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generates orders for the caller's tenant so the fetch strategies have something to measure.
 *
 * <p>Present because the comparison is only honest on realistic data: at ten orders every
 * strategy looks instant. Guarded by {@code platform-admin} and writes only into the bound
 * tenant, so it cannot be used to manufacture rows somewhere else.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Test-data generation for the benchmark")
public class SeedController {

    private final SeedService seeds;

    public SeedController(SeedService seeds) {
        this.seeds = seeds;
    }

    @Operation(summary = "Seed orders into the caller's tenant")
    @PostMapping("/seed")
    @PreAuthorize("hasRole('platform-admin')")
    public SeedResult seed(@RequestParam(defaultValue = "500") int orders,
                           @RequestParam(defaultValue = "8") int linesPerOrder) {
        long written = seeds.seed(orders, linesPerOrder);
        return new SeedResult(TenantContext.requireTenant(), written, (long) orders * linesPerOrder);
    }

    public record SeedResult(String tenantId, long ordersWritten, long linesWritten) {
    }

    @Service
    static class SeedService {

        private static final String[] SKUS = {
                "SKU-KEYBOARD", "SKU-MOUSE", "SKU-MONITOR", "SKU-DOCK",
                "SKU-CABLE", "SKU-WEBCAM", "SKU-HEADSET", "SKU-STAND"
        };

        private final OrderRepository orders;

        SeedService(OrderRepository orders) {
            this.orders = orders;
        }

        @Transactional
        long seed(int orderCount, int linesPerOrder) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            List<OrderEntity> batch = new ArrayList<>(orderCount);
            Instant now = Instant.now();

            for (int i = 0; i < orderCount; i++) {
                OrderEntity order = new OrderEntity(
                        "SEED-%s-%06d".formatted(
                                Long.toHexString(now.toEpochMilli()).toUpperCase(), i),
                        "Customer %04d".formatted(random.nextInt(1000)),
                        now.minus(random.nextInt(90 * 24), ChronoUnit.HOURS));
                for (int l = 0; l < linesPerOrder; l++) {
                    order.addLine(new OrderLine(
                            SKUS[random.nextInt(SKUS.length)],
                            1 + random.nextInt(5),
                            BigDecimal.valueOf(random.nextInt(500, 25_000), 2)));
                }
                batch.add(order);
            }
            orders.saveAll(batch);
            return batch.size();
        }
    }
}
