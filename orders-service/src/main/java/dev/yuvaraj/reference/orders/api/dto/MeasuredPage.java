package dev.yuvaraj.reference.orders.api.dto;

import java.util.List;

/**
 * A page of orders plus what it cost to build, so the difference between strategies is
 * a number in the response body rather than a claim in a README.
 */
public record MeasuredPage(
        String strategy,
        int page,
        int size,
        long totalElements,
        int returnedElements,
        long jdbcStatements,
        long rowsMaterialised,
        long elapsedMillis,
        List<OrderView> content) {
}
