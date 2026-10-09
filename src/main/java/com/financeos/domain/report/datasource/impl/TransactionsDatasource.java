package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.ReportQueryBuilder;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.report.engine.TransactionQueryBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TransactionsDatasource implements ReportDatasource {

    /** Single source of truth for the transactions fields (also used by the list-page filter builder). */
    private static final List<FieldDef> FIELDS = DatasourceCatalog.transactionFields();

    private final TransactionQueryBuilder queryBuilder;

    /** Without billing cycles: cycle operators and the billing-cycle dimension are unavailable. */
    public TransactionsDatasource(SqlPredicates sqlPredicates, DateRangeResolver dateRangeResolver) {
        this(sqlPredicates, dateRangeResolver, null);
    }

    @Autowired
    public TransactionsDatasource(SqlPredicates sqlPredicates, DateRangeResolver dateRangeResolver,
                                  BillingCycleService billingCycleService) {
        Map<String, FieldDef> fieldsMap = FIELDS.stream().collect(Collectors.toMap(FieldDef::name, f -> f));
        this.queryBuilder = new TransactionQueryBuilder(fieldsMap, dateRangeResolver, sqlPredicates, billingCycleService);
    }

    @Override
    public String name() {
        return "transactions";
    }

    @Override
    public String label() {
        return "Transactions";
    }

    @Override
    public List<FieldDef> fields() {
        return FIELDS;
    }

    @Override
    public String billingCycleAccountField() {
        return "account";
    }

    /** A KPI's underlying rows are identified by date, description, account and category. */
    @Override
    public List<String> underlyingColumns() {
        return List.of("date", "description", "account", "category");
    }

    @Override
    public ReportQueryBuilder queryBuilder() {
        return queryBuilder;
    }
}
