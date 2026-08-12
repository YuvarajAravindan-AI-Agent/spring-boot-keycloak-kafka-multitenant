package dev.yuvaraj.reference.inventory.service;

import java.util.List;

import dev.yuvaraj.reference.inventory.api.dto.StockLevel;
import dev.yuvaraj.reference.inventory.config.RedisCacheConfig;
import dev.yuvaraj.reference.inventory.domain.StockItem;
import dev.yuvaraj.reference.inventory.repo.StockRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    /**
     * The cache key, written once and referenced by both the read and the eviction.
     *
     * <p>Spelling it out beats letting a {@code KeyGenerator} derive it from the method
     * arguments. {@code getStock(sku)} takes one parameter and {@code reserve(sku, quantity)}
     * takes two, so a generator that concatenates arguments produces {@code tenant:SKU-DOCK}
     * for the read and {@code tenant:SKU-DOCK:3} for the eviction. Nothing fails — the evict
     * simply deletes a key that was never written, and the stale entry survives. Sharing one
     * expression makes the two provably identical.
     */
    private static final String TENANT_SKU_KEY =
            "T(dev.yuvaraj.reference.security.TenantContext).requireTenant() + ':' + #sku";

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StockRepository stock;

    public InventoryService(StockRepository stock) {
        this.stock = stock;
    }

    /**
     * Cached read. The key comes from {@code tenantAwareKeyGenerator}, so the Redis entry is
     * {@code stock::<tenant>:<sku>} and two tenants asking for the same SKU never share a slot.
     */
    @Cacheable(cacheNames = RedisCacheConfig.STOCK_CACHE, key = TENANT_SKU_KEY)
    @Transactional(readOnly = true)
    public StockLevel getStock(String sku) {
        log.debug("cache miss for sku={}, reading Postgres", sku);
        return stock.findBySku(sku)
                .map(item -> new StockLevel(item.getSku(), item.getAvailable()))
                .orElseGet(() -> new StockLevel(sku, 0));
    }

    @Transactional(readOnly = true)
    public List<StockLevel> listAll() {
        return stock.findAllByOrderBySkuAsc().stream()
                .map(item -> new StockLevel(item.getSku(), item.getAvailable()))
                .toList();
    }

    /**
     * Takes stock for an order line and drops the cached entry for that SKU.
     *
     * <p>The eviction is the point. Without it the write lands in Postgres and the next read
     * is still served the pre-decrement number from Redis until the 10-minute TTL expires —
     * a bug that never reproduces locally (empty cache, always a miss) and is reported from
     * production as "stock is wrong sometimes". The {@code @CacheEvict} runs after the method
     * returns normally, so a failed reservation leaves the cache alone.
     */
    @CacheEvict(cacheNames = RedisCacheConfig.STOCK_CACHE, key = TENANT_SKU_KEY)
    @Transactional
    public boolean reserve(String sku, int quantity) {
        StockItem item = stock.findBySku(sku).orElse(null);
        if (item == null) {
            log.warn("sku={} unknown for this tenant", sku);
            return false;
        }
        boolean taken = item.tryReserve(quantity);
        if (taken) {
            stock.save(item);
            log.info("sku={} reserved {} -> {} remaining", sku, quantity, item.getAvailable());
        } else {
            log.info("sku={} refused: requested {}, available {}", sku, quantity, item.getAvailable());
        }
        return taken;
    }
}
