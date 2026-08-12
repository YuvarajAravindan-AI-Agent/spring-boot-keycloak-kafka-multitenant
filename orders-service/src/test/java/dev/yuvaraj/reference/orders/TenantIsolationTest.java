package dev.yuvaraj.reference.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import dev.yuvaraj.reference.orders.api.dto.FetchStrategy;
import dev.yuvaraj.reference.orders.domain.OrderEntity;
import dev.yuvaraj.reference.orders.domain.OrderLine;
import dev.yuvaraj.reference.orders.repo.OrderRepository;
import dev.yuvaraj.reference.orders.service.OrderQueryService;
import dev.yuvaraj.reference.security.TenantContext;
import dev.yuvaraj.reference.security.TenantMissingException;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that would have caught the leak.
 *
 * <p>Most multi-tenant test suites only ever exercise one tenant, which is why cross-tenant
 * reads survive to production: with a single tenant in the database, a missing
 * {@code WHERE tenant_id = ?} produces exactly the right answer. Every test here writes as
 * two tenants and then reads as one.
 */
class TenantIsolationTest extends AbstractPostgresTest {

    private static final String TENANT_A = "tenant-iso-a";
    private static final String TENANT_B = "tenant-iso-b";

    @Autowired
    private OrderRepository repository;

    @Autowired
    private OrderQueryService queries;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void seedBothTenants() throws Exception {
        seedIfEmpty(TENANT_A, "A-ORDER", 3);
        seedIfEmpty(TENANT_B, "B-ORDER", 5);
    }

    @Test
    @DisplayName("a tenant sees only its own orders")
    void readsAreScopedToTheBoundTenant() throws Exception {
        long a = TenantContext.callWith(TENANT_A, () -> transactions.execute(s -> repository.count()));
        long b = TenantContext.callWith(TENANT_B, () -> transactions.execute(s -> repository.count()));

        assertThat(a).isEqualTo(3);
        assertThat(b).isEqualTo(5);
    }

    @Test
    @DisplayName("a listing as tenant B never contains tenant A's rows")
    void listingDoesNotLeakAcrossTenants() throws Exception {
        List<String> refs = TenantContext.callWith(TENANT_B,
                () -> queries.list(FetchStrategy.TWO_QUERY, 0, 100).content()
                        .stream().map(o -> o.orderRef()).toList());

        assertThat(refs).isNotEmpty();
        assertThat(refs).allSatisfy(ref -> assertThat(ref).startsWith("B-ORDER"));
    }

    @Test
    @DisplayName("hand-written JPQL cannot reach another tenant either")
    void rawJpqlIsAlsoFiltered() throws Exception {
        // The interesting case. A developer bypassing the repository and writing their own
        // query is the usual way isolation gets lost -- but @TenantId is applied by Hibernate
        // at the mapping level, so this query is rewritten with the predicate too. There is no
        // "escape hatch" that quietly returns everything.
        Long count = TenantContext.callWith(TENANT_A, () -> transactions.execute(status ->
                entityManager.createQuery(
                        "select count(o) from OrderEntity o where o.orderRef like 'B-ORDER%'",
                        Long.class).getSingleResult()));

        assertThat(count)
                .as("tenant A asking for tenant B's rows by name still gets nothing")
                .isZero();
    }

    @Test
    @DisplayName("an unbound thread fails closed: no tenant, no rows")
    void unboundTenantReadsNothing() {
        TenantContext.clear();

        // The failure mode this prevents: a background job or a listener that forgot to bind a
        // tenant, quietly running unfiltered across every customer's data. The resolver returns
        // a sentinel that matches no real tenant, so the query comes back empty rather than
        // complete. Failing closed is the only safe default here -- an empty list gets
        // investigated, a full one gets shipped.
        var page = queries.list(FetchStrategy.TWO_QUERY, 0, 100);

        assertThat(page.totalElements())
                .as("no tenant bound must not mean every tenant")
                .isZero();
    }

    @Test
    @DisplayName("code that needs the tenant explicitly gets an exception, not a silent default")
    void requireTenantThrowsWhenUnbound() {
        TenantContext.clear();

        assertThatThrownBy(TenantContext::requireTenant)
                .isInstanceOf(TenantMissingException.class)
                .hasMessageContaining("No tenant bound");
    }

    private void seedIfEmpty(String tenant, String prefix, int count) throws Exception {
        TenantContext.callWith(tenant, () -> transactions.execute(status -> {
            if (repository.count() > 0) {
                return null;
            }
            for (int i = 0; i < count; i++) {
                OrderEntity order = new OrderEntity(
                        "%s-%03d".formatted(prefix, i), "Customer " + i, Instant.now());
                order.addLine(new OrderLine("SKU-MOUSE", 1, new BigDecimal("9.99")));
                repository.save(order);
            }
            return null;
        }));
    }
}
