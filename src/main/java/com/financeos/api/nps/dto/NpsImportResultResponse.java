package com.financeos.api.nps.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What importing one NPS statement did (or, for a preview, would do). {@code adjustments} are the units the
 * statement's opening balance moved in the ledger; {@code unmapped} the printed scheme names that matched no
 * catalog scheme (their lines are skipped); {@code incomplete} a statement Protean sent without figures.
 */
public record NpsImportResultResponse(
        @Nullable UUID statementId,
        @Nullable UUID accountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int added,
        int upgraded,
        int skipped,
        List<Adjustment> adjustments,
        List<String> unmapped,
        boolean unitsMismatch,
        boolean incomplete,
        List<String> warnings) {

    @Schema(name = "NpsImportAdjustment")
    public record Adjustment(String schemeCode, @Nullable String schemeName, BigDecimal units) {
    }
}
