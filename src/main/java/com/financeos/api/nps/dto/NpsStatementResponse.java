package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsStatementSource;
import com.financeos.domain.nps.NpsTier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One imported NPS statement. */
public record NpsStatementResponse(
        UUID id,
        UUID accountId,
        NpsTier tier,
        LocalDate periodStart,
        LocalDate periodEnd,
        NpsStatementSource source,
        Instant importedAt,
        boolean unitsMismatch,
        boolean incomplete,
        int warningCount,
        List<String> warnings) {
}
