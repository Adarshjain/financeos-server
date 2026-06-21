package com.financeos.domain.report.engine;

import java.util.List;
import java.util.Map;

/** The computed result of a Table report: typed columns, row maps, and pagination info. */
public record TableData(
        String type,
        String mode,
        List<Column> columns,
        List<Map<String, Object>> rows,
        Page page) implements ReportData {

    public record Column(String key, String label, String type) {
    }

    public record Page(int number, int size, long totalElements, int totalPages) {
    }
}
