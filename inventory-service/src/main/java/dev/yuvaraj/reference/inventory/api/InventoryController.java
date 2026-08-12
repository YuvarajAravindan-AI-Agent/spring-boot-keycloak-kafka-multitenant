package dev.yuvaraj.reference.inventory.api;

import java.util.List;

import dev.yuvaraj.reference.inventory.api.dto.StockLevel;
import dev.yuvaraj.reference.inventory.service.InventoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory")
@Tag(name = "Inventory", description = "Tenant-scoped stock levels, read through Redis")
@SecurityRequirement(name = "keycloak")
public class InventoryController {

    private final InventoryService inventory;

    public InventoryController(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Operation(summary = "Read a stock level",
            description = "Served from Redis under key `stock::<tenant>:<sku>`. Called by "
                    + "orders-service over Feign with the end user's token relayed.")
    @GetMapping("/{sku}")
    public StockLevel get(@PathVariable String sku) {
        return inventory.getStock(sku);
    }

    @Operation(summary = "List all stock for the caller's tenant")
    @GetMapping
    public List<StockLevel> list() {
        return inventory.listAll();
    }
}
