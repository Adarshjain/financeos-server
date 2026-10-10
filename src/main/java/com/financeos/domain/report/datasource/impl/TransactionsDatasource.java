package com.financeos.domain.report.datasource.impl;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountRepository;
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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class TransactionsDatasource implements ReportDatasource {

    /** Single source of truth for the transactions fields (also used by the list-page filter builder). */
    private static final List<FieldDef> FIELDS = DatasourceCatalog.transactionFields();

    private final TransactionQueryBuilder queryBuilder;
    private AccountRepository accountRepository;

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

    /** Labels the account ids an app-built filter holds (see TransactionQueryBuilder) by account name. */
    @Autowired(required = false)
    public void setAccountRepository(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /** Account ids (the current user's) read as the account's name. */
    @Override
    public Map<String, String> filterValueLabels(String field, Collection<String> values) {
        UUID userId = UserContext.getCurrentUserId();
        if (!"account".equals(field) || accountRepository == null || userId == null) {
            return Map.of();
        }
        List<UUID> ids = values.stream().map(TransactionsDatasource::uuidOrNull)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> labels = new HashMap<>();
        accountRepository.findAllById(ids).stream()
                .filter(a -> a.getUser() != null && userId.equals(a.getUser().getId()))
                .forEach(a -> labels.put(a.getId().toString(), a.getName()));
        return labels;
    }

    private static UUID uuidOrNull(String value) {
        try {
            return value != null && value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public ReportQueryBuilder queryBuilder() {
        return queryBuilder;
    }
}
