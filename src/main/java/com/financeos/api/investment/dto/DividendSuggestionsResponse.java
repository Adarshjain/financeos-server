package com.financeos.api.investment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DividendSuggestionsResponse(
        List<Suggestion> suggestions,
        int scannedSymbols,
        List<String> skippedSymbols
) {
    public record Suggestion(
            UUID holdingId,
            UUID brokerAccountId,
            String brokerName,
            UUID instrumentId,
            String instrumentName,
            String symbol,
            LocalDate exDate,
            BigDecimal perUnit,
            BigDecimal qtyHeld,
            BigDecimal estimatedAmount
    ) {}
}
