package com.financeos.api.investment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Body of {@code POST /investments/dividends/reconciliation/confirm}: link several dividends at once. */
public record ConfirmDividendMatchesRequest(
        @NotEmpty List<@Valid Item> items
) {
    public record Item(
            @NotNull UUID dividendId,
            @NotNull UUID transactionId,
            boolean updateTds
    ) {}
}
