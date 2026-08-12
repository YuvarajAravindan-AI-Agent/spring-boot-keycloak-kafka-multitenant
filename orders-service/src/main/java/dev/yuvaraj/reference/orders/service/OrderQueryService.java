package dev.yuvaraj.reference.orders.service;

import java.util.List;

import dev.yuvaraj.reference.orders.api.dto.FetchStrategy;
import dev.yuvaraj.reference.orders.api.dto.MeasuredPage;
import dev.yuvaraj.reference.orders.api.dto.OrderView;
import dev.yuvaraj.reference.orders.domain.OrderEntity;
import dev.yuvaraj.reference.orders.repo.OrderRepository;
import dev.yuvaraj.reference.orders.support.StatementCounter;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads pages of orders using whichever fetch strategy the caller asked for, and reports
 * what it cost.
 *
 * <p>Every method is inside one transaction so the lazy collection in {@link FetchStrategy#NAIVE}
 * actually resolves instead of throwing {@code LazyInitializationException}. That detail matters:
 * plenty of teams "fix" an N+1 by moving mapping outside the transaction, which converts a
 * slow endpoint into a broken one and is usually reported as a different bug entirely.
 */
@Service
public class OrderQueryService {

    private final OrderRepository orders;
    private final StatementCounter counter;

    public OrderQueryService(OrderRepository orders, StatementCounter counter) {
        this.orders = orders;
        this.counter = counter;
    }

    /** Upper bound on {@code size}. A caller asking for a 100,000-row page is its own outage. */
    private static final int MAX_PAGE_SIZE = 200;

    @Transactional(readOnly = true)
    public MeasuredPage list(FetchStrategy strategy, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        StatementCounter.Snapshot before = counter.snapshot();

        List<OrderView> content;
        long total;

        switch (strategy) {
            case NAIVE -> {
                Page<OrderEntity> result = orders.findAllByOrderByPlacedAtDesc(pageable);
                total = result.getTotalElements();
                // Mapping touches getLines() on each row -> one SELECT per order.
                content = result.getContent().stream().map(OrderView::from).toList();
            }
            case JOIN_FETCH -> {
                List<OrderEntity> result = orders.findAllJoinFetchInMemoryPaged(pageable);
                total = orders.count();
                content = result.stream().map(OrderView::from).toList();
            }
            case TWO_QUERY -> {
                Page<Long> idPage = orders.findOrderIdPage(pageable);
                total = idPage.getTotalElements();
                content = idPage.getContent().isEmpty()
                        ? List.of()
                        : orders.findWithLinesByIds(idPage.getContent()).stream()
                                .map(OrderView::from)
                                .toList();
            }
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }

        StatementCounter.Snapshot after = counter.snapshot();
        return new MeasuredPage(
                strategy.name(),
                page,
                size,
                total,
                content.size(),
                after.statementsSince(before),
                after.rowsSince(before),
                after.millisSince(before),
                content);
    }
}
