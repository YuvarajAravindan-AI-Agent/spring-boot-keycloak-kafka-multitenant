package dev.yuvaraj.reference.orders.api;

import dev.yuvaraj.reference.orders.api.dto.FetchStrategy;
import dev.yuvaraj.reference.orders.api.dto.MeasuredPage;
import dev.yuvaraj.reference.orders.api.dto.OrderView;
import dev.yuvaraj.reference.orders.api.dto.PlaceOrderRequest;
import dev.yuvaraj.reference.orders.service.OrderCommandService;
import dev.yuvaraj.reference.orders.service.OrderQueryService;
import dev.yuvaraj.reference.security.TenantContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Tenant-scoped order management")
@SecurityRequirement(name = "keycloak")
public class OrderController {

    private final OrderQueryService queries;
    private final OrderCommandService commands;

    public OrderController(OrderQueryService queries, OrderCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @Operation(summary = "List orders for the caller's tenant",
            description = "The `strategy` parameter selects the collection fetch approach and the "
                    + "response reports the JDBC statements it cost. NAIVE is 1+N, JOIN_FETCH is one "
                    + "query paginated in memory, TWO_QUERY is the correct fix at two statements flat.")
    @GetMapping
    @PreAuthorize("hasRole('orders:read')")
    public MeasuredPage list(
            @RequestParam(defaultValue = "TWO_QUERY") FetchStrategy strategy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.list(strategy, page, size);
    }

    @Operation(summary = "Place an order",
            description = "Checks stock through Feign, persists, then publishes orders.placed "
                    + "after commit. Inventory replies asynchronously and the order moves to "
                    + "RESERVED or REJECTED.")
    @PostMapping
    @PreAuthorize("hasRole('orders:write')")
    public ResponseEntity<OrderView> place(@Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(commands.place(request));
    }

    @Operation(summary = "Echo the tenant resolved from the access token",
            description = "Handy when demonstrating that the tenant comes from a signed claim "
                    + "rather than a header the caller controls.")
    @GetMapping("/whoami")
    public WhoAmI whoami() {
        return new WhoAmI(TenantContext.requireTenant());
    }

    public record WhoAmI(String tenantId) {
    }
}
