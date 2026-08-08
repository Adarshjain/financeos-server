package com.financeos.domain.report.datasource;

import com.financeos.domain.report.engine.ReportQueryBuilder;
import java.util.List;
import java.util.Map;

public interface ComputedReportDatasource extends ReportDatasource {
    /**
     * One map per row, keyed by catalog field name.
     * Values: String | BigDecimal | LocalDate | Boolean | null.
     */
    List<Map<String, Object>> rows();

    @Override
    default ReportQueryBuilder queryBuilder() {
        return null;
    }
}
