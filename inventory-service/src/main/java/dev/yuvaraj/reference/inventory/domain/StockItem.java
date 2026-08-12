package dev.yuvaraj.reference.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "stock_items")
public class StockItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private int available;

    /**
     * Optimistic lock. Two OrderPlaced events for the same SKU can be handled concurrently by
     * different partitions' listener threads; without this, the classic read-modify-write race
     * loses one of the decrements and the stock count drifts upward over time. On conflict
     * Spring Kafka retries the record, which is safe because the handler is idempotent per
     * order reference.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected StockItem() {
        // for JPA
    }

    public StockItem(String sku, int available) {
        this.sku = sku;
        this.available = available;
    }

    /** @return true if the full quantity was taken */
    public boolean tryReserve(int quantity) {
        if (available < quantity) {
            return false;
        }
        available -= quantity;
        return true;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailable() {
        return available;
    }

    public long getVersion() {
        return version;
    }
}
