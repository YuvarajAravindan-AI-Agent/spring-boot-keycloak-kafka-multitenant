package dev.yuvaraj.reference.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/**
 * An order and its lines.
 *
 * <p>{@code lines} is deliberately {@code LAZY}. Making it {@code EAGER} would hide the N+1
 * this repository demonstrates rather than fix it &mdash; an eager collection on a
 * {@code findAll()} produces the same one-query-per-row pattern, just earlier and with no
 * way for a caller that does not need the lines to opt out.
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Hibernate fills and filters this column itself, from the
     * {@code CurrentTenantIdentifierResolver}. It is intentionally not settable through the
     * constructor or a setter: application code that can choose its own tenant id is
     * application code that can get it wrong.
     */
    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "order_ref", nullable = false)
    private String orderRef;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private List<OrderLine> lines = new ArrayList<>();

    protected OrderEntity() {
        // for JPA
    }

    public OrderEntity(String orderRef, String customerName, Instant placedAt) {
        this.orderRef = orderRef;
        this.customerName = customerName;
        this.placedAt = placedAt;
        this.status = OrderStatus.PLACED;
    }

    public void addLine(OrderLine line) {
        line.attachTo(this);
        this.lines.add(line);
        this.totalAmount = this.totalAmount.add(line.lineTotal());
    }

    public void markStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    public Long getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public String getCustomerName() {
        return customerName;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public List<OrderLine> getLines() {
        return lines;
    }
}
