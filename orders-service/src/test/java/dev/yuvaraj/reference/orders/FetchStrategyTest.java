package dev.yuvaraj.reference.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import dev.yuvaraj.reference.orders.api.dto.FetchStrategy;
import dev.yuvaraj.reference.orders.api.dto.MeasuredPage;
import dev.yuvaraj.reference.orders.domain.OrderEntity;
import dev.yuvaraj.reference.orders.domain.OrderLine;
import dev.yuvaraj.reference.orders.repo.OrderRepository;
import dev.yuvaraj.reference.orders.service.OrderQueryService;
import dev.yuvaraj.reference.security.TenantContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the cost of each fetch strategy, so a regression shows up as a failing assertion
 * rather than as a slow endpoint six months later.
 *
 * <p>Asserting on statement <em>counts</em> instead of elapsed time is what makes this test
 * meaningful in CI: wall-clock thresholds on a shared runner are flaky, but "this page must
 * cost three statements" is exact and fails for exactly one reason.
 */
class FetchStrategyTest extends AbstractPostgresTest {

    private static final String TENANT = "tenant-fetch-test";
    private static final int ORDERS = 200;
    private static final int LINES_PER_ORDER = 8;
    private static final int PAGE_SIZE = 20;

    @Autowired
    private OrderQueryService queries;

    @Autowired
    private OrderRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void seedOnce() throws Exception {
        TenantContext.callWith(TENANT, () -> transactions.execute(status -> {
            if (repository.count() > 0) {
                return null;
            }
            Instant now = Instant.now();
            for (int i = 0; i < ORDERS; i++) {
                OrderEntity order = new OrderEntity(
                        "FETCH-%04d".formatted(i), "Customer " + i, now.minus(i, ChronoUnit.MINUTES));
                for (int l = 0; l < LINES_PER_ORDER; l++) {
                    order.addLine(new OrderLine("SKU-" + l, 1 + l, new BigDecimal("19.99")));
                }
                repository.save(order);
            }
            return null;
        }));
    }

    @Test
    @DisplayName("NAIVE issues one extra statement per row in the page")
    void naiveIsOnePlusN() throws Exception {
        MeasuredPage page = listWith(FetchStrategy.NAIVE);

        assertThat(page.returnedElements()).isEqualTo(PAGE_SIZE);
        // 1 page query + 1 count + one collection SELECT per order.
        assertThat(page.jdbcStatements())
                .as("N+1: a statement per order in the page")
                .isGreaterThanOrEqualTo(PAGE_SIZE);
    }

    @Test
    @DisplayName("JOIN_FETCH costs few statements but materialises the whole table")
    void joinFetchPaginatesInMemory() throws Exception {
        MeasuredPage page = listWith(FetchStrategy.JOIN_FETCH);

        assertThat(page.jdbcStatements())
                .as("one join fetch plus the count")
                .isLessThanOrEqualTo(3);

        // The trap: statement count looks excellent while every row in the table is loaded
        // into the persistence context and then discarded. This is the strategy that passes
        // review, passes a small-dataset test, and falls over in production.
        assertThat(page.rowsMaterialised())
                .as("every order and line in the table, not just the requested page")
                .isGreaterThanOrEqualTo((long) ORDERS);
    }

    @Test
    @DisplayName("TWO_QUERY is flat: constant statements, only the page materialised")
    void twoQueryIsFlat() throws Exception {
        MeasuredPage page = listWith(FetchStrategy.TWO_QUERY);

        assertThat(page.returnedElements()).isEqualTo(PAGE_SIZE);
        assertThat(page.jdbcStatements())
                .as("id page + count + collection fetch")
                .isLessThanOrEqualTo(3);

        MeasuredPage naive = listWith(FetchStrategy.NAIVE);
        assertThat(page.jdbcStatements())
                .as("the whole point of the fix")
                .isLessThan(naive.jdbcStatements());

        MeasuredPage joinFetch = listWith(FetchStrategy.JOIN_FETCH);
        assertThat(page.rowsMaterialised())
                .as("and it does not pay JOIN_FETCH's hidden cost either")
                .isLessThan(joinFetch.rowsMaterialised());
    }

    @Test
    @DisplayName("every strategy returns the same page content")
    void strategiesAgree() throws Exception {
        MeasuredPage naive = listWith(FetchStrategy.NAIVE);
        MeasuredPage twoQuery = listWith(FetchStrategy.TWO_QUERY);

        // A faster query that returns different rows is not an optimisation.
        assertThat(twoQuery.content().stream().map(o -> o.orderRef()).toList())
                .containsExactlyElementsOf(naive.content().stream().map(o -> o.orderRef()).toList());
        assertThat(twoQuery.content().get(0).lines())
                .hasSize(LINES_PER_ORDER)
                .containsExactlyInAnyOrderElementsOf(naive.content().get(0).lines());
    }

    private MeasuredPage listWith(FetchStrategy strategy) throws Exception {
        return TenantContext.callWith(TENANT, () -> queries.list(strategy, 0, PAGE_SIZE));
    }
}
