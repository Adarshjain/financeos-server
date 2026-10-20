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
    String failureReason,
    String summaryText
) {
    public static StatementExtractionResult success(List<ParsedStatementLine> lines, String accountNumber, LocalDate periodStart, LocalDate periodEnd, StatementDraft draft) {
        return new StatementExtractionResult(true, lines, accountNumber, periodStart, periodEnd, draft, null, null);
    }

    /** The same result with the leading/trailing summary text (for the AI details fallback only). */
    public StatementExtractionResult withSummaryText(String text) {
        return new StatementExtractionResult(success, lines, accountNumber, periodStart, periodEnd, draft, failureReason, text);
    }

    /** The same result with a draft that replaced the parsed one; the result's period follows the draft. */
    public StatementExtractionResult withDraft(StatementDraft newDraft) {
        return new StatementExtractionResult(success, lines, accountNumber, newDraft.periodStart(), newDraft.periodEnd(),
                newDraft, failureReason, summaryText);
    }

    public static StatementExtractionResult failure(String reason) {
        return new StatementExtractionResult(false, List.of(), null, null, null, null, reason, null);
    }
}
