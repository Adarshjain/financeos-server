package com.financeos.api.linksuggestion.dto;

import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/** One stored suggestion: the transaction that needs a partner and its candidates, best first. */
public record LinkSuggestionResponse(
        UUID id,
        SuggestionKind kind,
        TransactionSummary transaction,
        List<TransactionSummary> candidates
) {
    public record TransactionSummary(
            UUID id,
            LocalDate date,
            BigDecimal amount,
            TransactionType type,
            @Nullable String description,
            String accountName
    ) {}
}
