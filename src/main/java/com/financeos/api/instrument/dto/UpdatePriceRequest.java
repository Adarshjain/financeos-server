package com.financeos.api.instrument.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record UpdatePriceRequest(
        @NotNull(message = "Price is required")
        @PositiveOrZero(message = "Price must be non-negative")
        BigDecimal price
) {
}
