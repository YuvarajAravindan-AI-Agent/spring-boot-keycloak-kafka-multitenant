package dev.yuvaraj.reference.inventory.repo;

import java.util.List;
import java.util.Optional;

import dev.yuvaraj.reference.inventory.domain.StockItem;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<StockItem, Long> {

    /** Tenant-scoped by the {@code @TenantId} mapping, not by a parameter. */
    Optional<StockItem> findBySku(String sku);

    List<StockItem> findAllByOrderBySkuAsc();
}
