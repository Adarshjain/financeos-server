package com.financeos.api.instrument.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdatePriceRequest(
        @NotNull(message = "Price is required")
        @PositiveOrZero(message = "Price must be non-negative")
        BigDecimal price,
        /** New date of the price; absent or null keeps it. */
        LocalDate asOf
) {
    /** Price only: the date stays. */
    public UpdatePriceRequest(BigDecimal price) {
        this(price, null);
    }
}
