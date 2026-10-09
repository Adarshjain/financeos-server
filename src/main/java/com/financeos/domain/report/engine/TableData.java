package com.financeos.domain.report.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The computed result of a Table report: typed columns, row maps, and pagination info.
 * {@code sortKey}/{@code sortDirection} echo a runtime sort the rows were ordered by (breakdown
 * sections); both are omitted when the rows are in their default order.
 */
public record TableData(
        String type,
        String mode,
        List<Column> columns,
        List<Map<String, Object>> rows,
        Page page,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sortKey,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sortDirection) implements ReportData {

    public TableData(String type, String mode, List<Column> columns, List<Map<String, Object>> rows, Page page) {
        this(type, mode, columns, rows, page, null, null);
    }

    /**
     * One column. {@code valueLabels} (static enum fields only, else omitted) says how each stored
     * value in the column reads for people; the rows keep the stored values.
     */
    public record Column(String key, String label, String type, String format,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> valueLabels) {

        public Column(String key, String label, String type, String format) {
            this(key, label, type, format, null);
        }
    }

    public record Page(int number, int size, long totalElements, int totalPages) {
    }
}
