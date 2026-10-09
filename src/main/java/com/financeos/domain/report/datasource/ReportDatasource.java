package com.financeos.domain.report.datasource;

import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportQueryBuilder;

import java.util.List;

public interface ReportDatasource {
    String name();                    // e.g. "transactions"
    String label();                   // e.g. "Transactions" (client display)
    List<FieldDef> fields();

    default FieldDef field(String name) {
        if (name == null || fields() == null) {
            return null;
        }
        return fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    ReportQueryBuilder queryBuilder();

    /**
     * The field that picks one account, required (as a single-value filter) whenever a report
     * uses billing cycles; null when the datasource has no billing cycles.
     */
    default String billingCycleAccountField() {
        return null;
    }

    /**
     * The identifying columns of a KPI's underlying rows, in display order (the KPI's measure is
     * appended last); null to use the executor fallback (first DATE field plus up to three
     * table dimensions).
     */
    default List<String> underlyingColumns() {
        return null;
    }

    /**
     * The order of a KPI's underlying rows when no runtime sort is given; keys may be any field of
     * this datasource. Null to use the executor default (first DATE field descending).
     */
    default List<SortClause> underlyingDefaultSort() {
        return null;
    }

    /**
     * The field the client groups a KPI's underlying rows by while the default order applies;
     * null for no grouping.
     */
    default String underlyingGroupField() {
        return null;
    }
}
