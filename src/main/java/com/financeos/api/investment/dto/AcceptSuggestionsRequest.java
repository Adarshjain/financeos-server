package com.financeos.api.investment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AcceptSuggestionsRequest(
        @NotEmpty List<@Valid Item> items
) {
    public record Item(
            @NotNull UUID holdingId,
            @NotNull LocalDate exDate,
            @NotNull LocalDate payDate,
            @NotNull @Positive BigDecimal amount,
            @Nullable BigDecimal perUnit,
            @Nullable String notes
    ) {}
}
