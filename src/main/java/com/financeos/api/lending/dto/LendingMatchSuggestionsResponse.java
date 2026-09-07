package com.financeos.api.lending.dto;

import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.domain.lending.LendingDirection;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LendingMatchSuggestionsResponse(
        List<LendingMatchSuggestion> suggestions
) {
    public record LendingMatchSuggestion(
            UUID lendingId,
            LendingDirection direction,
            BigDecimal amount,
            LocalDate entryDate,
            @Nullable LocalDate expectedReturnDate,
            @Nullable String notes,
            List<TransactionResponse> candidates
    ) {}
}
