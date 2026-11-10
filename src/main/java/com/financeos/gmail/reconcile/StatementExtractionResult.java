package com.financeos.gmail.reconcile;

import com.financeos.domain.statement.StatementDraft;
import com.financeos.statement.parser.StatementParseException;

import java.time.LocalDate;
import java.util.List;

/**
 * failureKind: why the parse failed (null on success, or when built without one); issuerId: the parser's bank/issuer
 * id (a bounded set), null when the parse failed or the issuer was not unique.
 */
public record StatementExtractionResult(
    boolean success,
    List<ParsedStatementLine> lines,
    String accountNumber,
    LocalDate periodStart,
    LocalDate periodEnd,
    StatementDraft draft,
    String failureReason,
    String summaryText,
    StatementParseException.Kind failureKind,
    String issuerId
) {
    public StatementExtractionResult(boolean success, List<ParsedStatementLine> lines, String accountNumber,
                                     LocalDate periodStart, LocalDate periodEnd, StatementDraft draft,
                                     String failureReason, String summaryText) {
        this(success, lines, accountNumber, periodStart, periodEnd, draft, failureReason, summaryText, null, null);
    }

    public static StatementExtractionResult success(List<ParsedStatementLine> lines, String accountNumber, LocalDate periodStart, LocalDate periodEnd, StatementDraft draft) {
        return new StatementExtractionResult(true, lines, accountNumber, periodStart, periodEnd, draft, null, null);
    }

    /** The same result with the leading/trailing summary text (for the AI details fallback only). */
    public StatementExtractionResult withSummaryText(String text) {
        return new StatementExtractionResult(success, lines, accountNumber, periodStart, periodEnd, draft, failureReason, text, failureKind, issuerId);
    }

    /** The same result with the parser's issuer id. */
    public StatementExtractionResult withIssuerId(String id) {
        return new StatementExtractionResult(success, lines, accountNumber, periodStart, periodEnd, draft, failureReason, summaryText, failureKind, id);
    }

    /** The same result with a draft that replaced the parsed one; the result's period follows the draft. */
    public StatementExtractionResult withDraft(StatementDraft newDraft) {
        return new StatementExtractionResult(success, lines, accountNumber, newDraft.periodStart(), newDraft.periodEnd(),
                newDraft, failureReason, summaryText, failureKind, issuerId);
    }

    public static StatementExtractionResult failure(String reason) {
        return new StatementExtractionResult(false, List.of(), null, null, null, null, reason, null, null, null);
    }

    public static StatementExtractionResult failure(String reason, StatementParseException.Kind kind) {
        return new StatementExtractionResult(false, List.of(), null, null, null, null, reason, null, kind, null);
    }
}
