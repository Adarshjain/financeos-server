package com.financeos.statement.parser;

import java.util.LinkedHashMap;
import java.util.List;

public record ParsedStatement(StatementMeta meta, String statementType,
                       LinkedHashMap<String, Object> summaryFields, Derived derived,
                       ParseInfo parseInfo, List<RowResult> transactions) {
}
