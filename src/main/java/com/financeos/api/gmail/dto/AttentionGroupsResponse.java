package com.financeos.api.gmail.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The grouped parts of Gmail "Needs attention": unknown numbers, accounts with import off, and ignored numbers. */
public record AttentionGroupsResponse(
        List<UnknownNumberGroup> unknownNumbers,
        List<ImportOffGroup> importOff,
        List<IgnoredNumberResponse> ignored,
        /** What the card header counts: unknown-number groups + import-off accounts + ungrouped emails (non-retryable). */
        int total
) {

    /** Unresolved mail that names the same number at the same bank; {@code bank} is the catalog bank id, null when unknown. */
    public record UnknownNumberGroup(
            @Nullable String bank,
            String last4,
            int count,
            Instant firstMail,
            Instant lastMail,
            /** ALERT and / or STATEMENT. */
            List<String> kinds,
            /** The one account that already holds the transactions these mails describe; null when none or unclear. */
            @Nullable NumberSuggestion suggestion
    ) {}

    /** {@code matched} mails each match exactly one transaction, all on this account; {@code total} mails were checked. */
    public record NumberSuggestion(UUID accountId, String accountName, int matched, int total, SuggestionExample example) {}

    public record SuggestionExample(LocalDate date, BigDecimal amount, @Nullable String description) {}

    /** An open account whose Gmail import is off and that has waiting mail. */
    public record ImportOffGroup(UUID accountId, String accountName, int count, Instant firstMail, Instant lastMail) {}

    public record IgnoredNumberResponse(UUID id, @Nullable String bank, String last4, Instant createdAt, int count) {}
}
