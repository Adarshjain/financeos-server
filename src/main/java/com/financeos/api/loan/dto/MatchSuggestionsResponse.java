package com.financeos.api.loan.dto;

import com.financeos.api.transaction.dto.TransactionResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MatchSuggestionsResponse(
        List<InstallmentMatchSuggestion> suggestions
) {
    public record InstallmentMatchSuggestion(
            Integer installmentSeq,
            LocalDate dueDate,
            BigDecimal expectedAmount,
            List<TransactionResponse> candidates
    ) {}
}
