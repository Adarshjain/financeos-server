package com.financeos.gmail.reconcile;

import java.util.List;

public record StatementExtractionResult(
    boolean success,
    List<ParsedStatementLine> lines,
    String accountLast4,
    String failureReason
) {
    public static StatementExtractionResult success(List<ParsedStatementLine> lines, String accountLast4) {
        return new StatementExtractionResult(true, lines, accountLast4, null);
    }
    
    public static StatementExtractionResult failure(String reason) {
        return new StatementExtractionResult(false, List.of(), null, reason);
    }
}
