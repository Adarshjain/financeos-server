package com.financeos.domain.report.engine;

import java.util.List;
import java.util.Map;

/**
 * The computed result of an aggregated (pivot) table: row groups × column groups × measures.
 * The client builds a matrix with {@code rowDimensions} on the left, {@code columns × measures}
 * across the top, and {@code rows[].cells[columnKey][measureKey]} in the body. When there are no
 * column dimensions, a single column with key {@code ""} carries the measures.
 */
public record PivotTableData(
        String type,
        String mode,
        List<DimensionInfo> rowDimensions,
        List<DimensionInfo> columnDimensions,
        List<MeasureInfo> measures,
        List<ColumnHeader> columns,
        List<Row> rows,
        TableData.Page page) implements ReportData {

    public record DimensionInfo(String field, String label) {
    }

    public record MeasureInfo(String key, String field, String aggregation, String label) {
    }

    public record ColumnHeader(String key, Map<String, String> values) {
    }

    public record Row(String key, Map<String, String> values, Map<String, Map<String, Object>> cells) {
    }
}
