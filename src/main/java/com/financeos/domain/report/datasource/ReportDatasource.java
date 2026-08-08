package com.financeos.domain.report.datasource;

import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
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
}
