package dev.yuvaraj.reference.orders.api.dto;

/**
 * Selects how {@code GET /api/orders} loads the line collection.
 *
 * <p>Exposed as a query parameter purely so the three behaviours can be compared against
 * the same data in the same process. Production code would only ever have {@link #TWO_QUERY}.
 */
public enum FetchStrategy {

    /** Lazy collection, resolved per row: 1 + N queries. */
    NAIVE,

    /** Single {@code join fetch}, but paginated in application memory (HHH90003004). */
    JOIN_FETCH,

    /** Id page in the database, then one collection fetch: 2 queries, always. */
    TWO_QUERY
}
