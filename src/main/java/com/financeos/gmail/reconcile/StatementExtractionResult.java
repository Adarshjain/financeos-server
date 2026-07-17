package com.financeos.gmail.reconcile;

import com.financeos.domain.statement.StatementDraft;

import java.time.LocalDate;
import java.util.List;

public record StatementExtractionResult(
    boolean success,
    List<ParsedStatementLine> lines,
    String accountNumber,
    LocalDate periodStart,
    LocalDate periodEnd,
    StatementDraft draft,
    String failureReason
) {
    public static StatementExtractionResult success(List<ParsedStatementLine> lines, String accountNumber, LocalDate periodStart, LocalDate periodEnd, StatementDraft draft) {
        return new StatementExtractionResult(true, lines, accountNumber, periodStart, periodEnd, draft, null);
    }

    public static StatementExtractionResult failure(String reason) {
        return new StatementExtractionResult(false, List.of(), null, null, null, null, reason);
    }
}
