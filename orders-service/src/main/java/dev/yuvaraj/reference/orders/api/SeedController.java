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

import org.springframework.beans.factory.annotation.Value;

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

    @Operation(summary = "Seed orders into the caller's tenant",
            description = "Bounded by platform.demo.max-orders-per-tenant. On the public demo "
                    + "the credentials are published, so an unbounded write endpoint is a disk-fill "
                    + "waiting to happen.")
    @PostMapping("/seed")
    @PreAuthorize("hasRole('platform-admin')")
    public SeedResult seed(@RequestParam(defaultValue = "500") int orders,
                           @RequestParam(defaultValue = "8") int linesPerOrder) {
        SeedService.Written written = seeds.seed(orders, linesPerOrder);
        return new SeedResult(TenantContext.requireTenant(), written.orders(), written.lines());
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
        private final int maxPerRequest;
        private final int maxPerTenant;
        private final int maxLinesPerOrder;

        SeedService(OrderRepository orders,
                    @Value("${platform.demo.max-orders-per-request:1000}") int maxPerRequest,
                    @Value("${platform.demo.max-orders-per-tenant:5000}") int maxPerTenant,
                    @Value("${platform.demo.max-lines-per-order:20}") int maxLinesPerOrder) {
            this.orders = orders;
            this.maxPerRequest = maxPerRequest;
            this.maxPerTenant = maxPerTenant;
            this.maxLinesPerOrder = maxLinesPerOrder;
        }

        /**
         * What was actually written, not what was asked for. Returning the requested figures
         * had the endpoint claim 999,000 lines while writing 20,000 — a response that
         * contradicted the database it had just written to.
         */
        record Written(long orders, long lines) {
        }

        /**
         * Writes at most {@code maxPerRequest} orders, and never takes the tenant beyond
         * {@code maxPerTenant} in total.
         *
         * <p>Clamping rather than rejecting: a visitor who asks for a million orders gets the
         * ceiling and a response that still demonstrates the point, instead of an error that
         * reads as a broken demo. The count is re-checked here rather than trusted from the
         * request, because the request is the thing under attack.
         */
        @Transactional
        Written seed(int requestedOrders, int requestedLines) {
            // Math.clamp is Java 21; this module targets 17.
            int linesPerOrder = Math.min(Math.max(requestedLines, 1), maxLinesPerOrder);
            int orderCount = Math.min(Math.max(requestedOrders, 0), maxPerRequest);

            long existing = orders.count();
            long headroom = Math.max(0, maxPerTenant - existing);
            orderCount = (int) Math.min(orderCount, headroom);
            if (orderCount == 0) {
                return new Written(0, 0);
            }

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
            return new Written(batch.size(), (long) batch.size() * linesPerOrder);
        }
    }
}
