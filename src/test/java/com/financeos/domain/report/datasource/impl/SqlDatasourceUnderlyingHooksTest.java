package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The KPI underlying-data hooks of the SQL datasources: identifying columns, date first, default order. */
class SqlDatasourceUnderlyingHooksTest {

    private final DateRangeResolver resolver = new DateRangeResolver(4);
    private final SqlPredicates predicates = new SqlPredicates(resolver);

    private void assertHooks(ReportDatasource ds, List<String> expected) {
        assertEquals(expected, ds.underlyingColumns());
        for (String column : expected) {
            FieldDef field = ds.field(column);
            assertNotNull(field, column);
            assertTrue(field.allowedInReports().contains(ReportType.TABLE), column + " is a table column");
            // Every column maps to a SQL expression the raw table can select.
            assertNotNull(ds.queryBuilder().expression(column, new HashSet<>()), column);
        }
        assertEquals(FieldType.DATE, ds.field(expected.get(0)).type(), "the date comes first");
        assertNull(ds.underlyingDefaultSort(), "the executor's newest-first default applies");
        assertNull(ds.underlyingGroupField());
    }

    @Test
    void transactions() {
        assertHooks(new TransactionsDatasource(predicates, resolver), List.of("date", "description", "account", "category"));
    }

    @Test
    void investmentTrades() {
        assertHooks(new InvestmentTradesDatasource(predicates, resolver), List.of("tradeDate", "instrument", "type", "broker"));
    }

    @Test
    void dividends() {
        assertHooks(new DividendsDatasource(predicates, resolver), List.of("payDate", "instrument", "type", "broker"));
    }

    @Test
    void fnoTrades() {
        assertHooks(new FnoTradesDatasource(predicates, resolver), List.of("exitDate", "tradingSymbol", "contractType", "broker"));
    }
}
