package com.financeos.api.account.dto;

import com.financeos.domain.statement.skip.StatementSkippedPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One cycle marked "No statement this period". */
public record StatementSkipResponse(UUID id, LocalDate periodStart, LocalDate periodEnd, Instant createdAt) {
    public static StatementSkipResponse from(StatementSkippedPeriod s) {
        return new StatementSkipResponse(s.getId(), s.getPeriodStart(), s.getPeriodEnd(), s.getCreatedAt());
    }
}
