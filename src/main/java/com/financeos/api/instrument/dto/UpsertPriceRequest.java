package com.financeos.api.instrument.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpsertPriceRequest(
        @NotNull(message = "Price is required") BigDecimal price,
        LocalDate asOf
) {
}
