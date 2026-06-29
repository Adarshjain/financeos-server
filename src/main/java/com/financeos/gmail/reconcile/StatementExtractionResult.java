package com.financeos.gmail.reconcile;

import java.util.List;

public record StatementExtractionResult(
    boolean success,
    List<ParsedStatementLine> lines,
    String failureReason
) {
    public static StatementExtractionResult success(List<ParsedStatementLine> lines) {
        return new StatementExtractionResult(true, lines, null);
    }
    
    public static StatementExtractionResult failure(String reason) {
        return new StatementExtractionResult(false, List.of(), reason);
    }
}
