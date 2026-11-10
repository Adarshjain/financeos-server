package com.financeos.statement.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * What a reader (v1 or a layout reader) hands to the shared finish step: meta, signed rows with balances and chain
 * flags, the mode string, rows dropped so far, the lines that are transaction rows, the harvest window and the
 * harvested summary fields.
 */
public record ReadOutcome(StatementMeta meta, List<RowResult> results, String mode, int rowsDropped,
                          Set<Line> txnLines, int maxTxnPage, int harvestMaxPage,
                          LinkedHashMap<String, Object> summaryFields, Set<String> statedNone,
                          boolean isCreditCard) {
}
