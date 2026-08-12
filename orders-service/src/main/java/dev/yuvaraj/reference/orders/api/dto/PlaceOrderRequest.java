package dev.yuvaraj.reference.orders.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record PlaceOrderRequest(
        @NotBlank String customerName,
        @NotEmpty @Valid List<Line> lines) {

    public record Line(
            @NotBlank String sku,
            @Min(1) int quantity,
            @DecimalMin("0.00") BigDecimal unitPrice) {
    }
}
