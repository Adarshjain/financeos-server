package com.financeos.statement.parser;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * summaryText: the statement's leading and trailing summary pages as plain lines (for the AI details fallback only).
 * layoutId / reader: which reader produced the result ("v1" or "layout") and, for "layout", the profile id; both
 * nullable (null when not routed through the engine).
 */
public record ParsedStatement(StatementMeta meta, String statementType,
                       LinkedHashMap<String, Object> summaryFields, Derived derived,
                       ParseInfo parseInfo, List<RowResult> transactions,
                       String summaryText, String layoutId, String reader) {

    public ParsedStatement(StatementMeta meta, String statementType, LinkedHashMap<String, Object> summaryFields,
                           Derived derived, ParseInfo parseInfo, List<RowResult> transactions, String summaryText) {
        this(meta, statementType, summaryFields, derived, parseInfo, transactions, summaryText, null, null);
    }

    public ParsedStatement withReader(String layoutId, String reader) {
        return new ParsedStatement(meta, statementType, summaryFields, derived, parseInfo, transactions, summaryText,
                layoutId, reader);
    }
}
