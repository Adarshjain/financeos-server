package com.financeos.domain.report.datasource;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceCatalog.ReportCatalogView;
import com.financeos.domain.report.datasource.DatasourceCatalog.SingleDatasourceView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DatasourceRegistry {

    private static final List<String> ORDERED_NAMES = List.of(
            "transactions",
            "investment_trades",
            "dividends",
            "fno_trades",
            "positions",
            "realized_lots",
            "portfolio_value",
            "loan_payments",
            "loan_tax_summary",
            "lendings",
            "reward_earnings"
    );

    private final Map<String, ReportDatasource> byNameMap;
    private final DatasourceCatalog catalog;

    public DatasourceRegistry(List<ReportDatasource> datasources, DatasourceCatalog catalog) {
        this.catalog = catalog;
        this.byNameMap = new LinkedHashMap<>();
        if (datasources != null) {
            for (ReportDatasource ds : datasources) {
                this.byNameMap.put(ds.name(), ds);
            }
        }
    }

    public ReportDatasource byName(String name) {
        if (name == null || !byNameMap.containsKey(name)) {
            throw new ValidationException("Unknown report datasource: " + name);
        }
        return byNameMap.get(name);
    }

    public boolean isKnown(String name) {
        return name != null && byNameMap.containsKey(name);
    }

    public FieldDef field(String datasource, String fieldName) {
        ReportDatasource ds = byNameMap.get(datasource);
        return ds != null ? ds.field(fieldName) : null;
    }

    public Set<String> operatorsFor(FieldType type) {
        return catalog.operatorsFor(type);
    }

    public ReportCatalogView view() {
        List<SingleDatasourceView> views = new ArrayList<>();
        for (String name : ORDERED_NAMES) {
            ReportDatasource ds = byNameMap.get(name);
            if (ds != null) {
                views.add(new SingleDatasourceView(ds.name(), ds.label(), ds.fields()));
            }
        }
        // Include any remaining registered datasources not in ORDERED_NAMES
        for (ReportDatasource ds : byNameMap.values()) {
            if (!ORDERED_NAMES.contains(ds.name())) {
                views.add(new SingleDatasourceView(ds.name(), ds.label(), ds.fields()));
            }
        }
        return new ReportCatalogView(views, DatasourceCatalog.OPERATORS);
    }
}
