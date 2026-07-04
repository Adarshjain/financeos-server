package com.financeos.gmail.reconcile;

import java.util.List;

public record StatementExtractionResult(
    boolean success,
    List<ParsedStatementLine> lines,
    String accountLast4,
    String statementPeriodStart,
    String statementPeriodEnd,
    String failureReason
) {
    public static StatementExtractionResult success(List<ParsedStatementLine> lines, String accountLast4, String statementPeriodStart, String statementPeriodEnd) {
        return new StatementExtractionResult(true, lines, accountLast4, statementPeriodStart, statementPeriodEnd, null);
    }
    
    public static StatementExtractionResult failure(String reason) {
        return new StatementExtractionResult(false, List.of(), null, null, null, reason);
    }
}
