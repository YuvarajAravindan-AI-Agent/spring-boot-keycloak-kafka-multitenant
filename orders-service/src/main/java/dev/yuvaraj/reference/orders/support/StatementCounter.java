package dev.yuvaraj.reference.orders.support;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.stereotype.Component;

/**
 * Reads Hibernate's own counters so "this endpoint issues 41 queries" is measured, not estimated.
 *
 * <p>Uses {@code prepareStatementCount} rather than {@code queryExecutionCount}: the latter
 * counts HQL/criteria executions and misses the per-collection SELECTs that N+1 actually
 * consists of, which would make the naive strategy look identical to the fixed one.
 */
@Component
public class StatementCounter {

    private final Statistics statistics;

    public StatementCounter(EntityManagerFactory entityManagerFactory) {
        this.statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                statistics.getPrepareStatementCount(),
                statistics.getEntityLoadCount() + statistics.getCollectionLoadCount(),
                System.nanoTime());
    }

    public record Snapshot(long statements, long rows, long nanos) {

        public long statementsSince(Snapshot earlier) {
            return this.statements - earlier.statements;
        }

        public long rowsSince(Snapshot earlier) {
            return this.rows - earlier.rows;
        }

        public long millisSince(Snapshot earlier) {
            return (this.nanos - earlier.nanos) / 1_000_000L;
        }
    }
}
