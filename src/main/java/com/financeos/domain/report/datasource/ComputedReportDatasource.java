package com.financeos.domain.report.datasource;

import com.financeos.domain.report.engine.ReportQueryBuilder;
import org.springframework.lang.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface ComputedReportDatasource extends ReportDatasource {
    /**
     * One map per row, keyed by catalog field name.
     * Values: String | BigDecimal | LocalDate | Boolean | List&lt;String&gt; | null.
     * A List is a multi-valued enum (e.g. a transaction's categories): filters match any
     * element, and grouping by it counts the row under each element.
     */
    List<Map<String, Object>> rows();

    /**
     * Rows for a run whose date filter keeps only {@code hint.field()} inside
     * [{@code hint.from()}, {@code hint.to()}]. Implementations MAY skip computing rows
     * outside that window; the executor still applies every filter afterwards, so
     * returning extra rows is always safe. {@code hint} is null when the run is unbounded.
     */
    default List<Map<String, Object>> rows(@Nullable DateHint hint) {
        return rows();
    }

    /** The bounded window of a run's date filter (both ends inclusive). */
    record DateHint(String field, LocalDate from, LocalDate to) {
    }

    @Override
    default ReportQueryBuilder queryBuilder() {
        return null;
    }
}
