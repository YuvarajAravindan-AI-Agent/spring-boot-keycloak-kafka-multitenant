package dev.yuvaraj.reference.orders.repo;

import java.util.List;

import dev.yuvaraj.reference.orders.domain.OrderEntity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The three ways to page a list of orders with their lines, one of which is correct.
 *
 * <p>No method here takes a tenant parameter. Hibernate appends {@code tenant_id = ?} to
 * every statement from the {@code @TenantId} mapping, so the filter cannot be forgotten at
 * a call site. See {@code TenantIsolationTest} for the proof, including the JPQL that
 * tries to read across tenants and comes back empty.
 */
public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /**
     * Strategy 1 &mdash; naive. Pages the roots correctly, then every {@code getLines()} call
     * in the mapping layer triggers its own SELECT: 1 + N queries per page.
     */
    Page<OrderEntity> findAllByOrderByPlacedAtDesc(Pageable pageable);

    /**
     * Strategy 2 &mdash; {@code join fetch} with a {@code Pageable}. One query, but Hibernate
     * cannot apply {@code LIMIT} to a joined collection without losing rows, so it fetches
     * <em>the whole result set</em> and paginates in application memory, logging
     * {@code HHH90003004}. Fine on 200 rows, fatal on 200k: the fix that reads like a fix
     * and is still an outage.
     */
    @Query("select distinct o from OrderEntity o left join fetch o.lines order by o.placedAt desc")
    List<OrderEntity> findAllJoinFetchInMemoryPaged(Pageable pageable);

    /**
     * Strategy 3 &mdash; two queries, correct. Page the root ids in the database, then fetch
     * the collection for exactly those ids. Constant two round trips regardless of page
     * size, and {@code LIMIT}/{@code OFFSET} stay where they belong.
     */
    @Query("select o.id from OrderEntity o order by o.placedAt desc")
    Page<Long> findOrderIdPage(Pageable pageable);

    @Query("select distinct o from OrderEntity o left join fetch o.lines "
            + "where o.id in :ids order by o.placedAt desc")
    List<OrderEntity> findWithLinesByIds(@Param("ids") List<Long> ids);

    /** Used by the Kafka consumer path to move an order to RESERVED/REJECTED. */
    OrderEntity findByOrderRef(String orderRef);
}
