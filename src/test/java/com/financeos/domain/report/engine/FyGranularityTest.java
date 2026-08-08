package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.Granularity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

class FyGranularityTest {

    @Test
    void dateRangeResolverGetter() {
        DateRangeResolver resolver4 = new DateRangeResolver(4);
        assertEquals(4, resolver4.getFiscalYearStartMonth());

        DateRangeResolver resolver1 = new DateRangeResolver(1);
        assertEquals(1, resolver1.getFiscalYearStartMonth());
    }

    @Test
    void bucketExpressionSqlForAprilAndJan() {
        DateRangeResolver resolver4 = new DateRangeResolver(4);
        SqlPredicates sqlPredicates = new SqlPredicates(resolver4);
        TransactionsDatasource ds4 = new TransactionsDatasource(sqlPredicates, resolver4);

        String expr4 = ds4.queryBuilder().bucketExpression("t.date", Granularity.FY);
        assertEquals("ADD_MONTHS(TRUNC(ADD_MONTHS(t.date, -3), 'YYYY'), 3)", expr4);

        DateRangeResolver resolver1 = new DateRangeResolver(1);
        TransactionsDatasource ds1 = new TransactionsDatasource(new SqlPredicates(resolver1), resolver1);
        String expr1 = ds1.queryBuilder().bucketExpression("t.date", Granularity.FY);
        assertEquals("TRUNC(t.date, 'YYYY')", expr1);
    }

    @Test
    void bucketLabelFormatting() {
        LocalDate bucket = LocalDate.of(2025, 4, 1);
        String label4 = BucketLabels.bucketLabel(bucket, Granularity.FY, 4);
        assertEquals("FY 25-26", label4);

        String label1 = BucketLabels.bucketLabel(bucket, Granularity.FY, 1);
        assertEquals("2025", label1);
    }

    @Test
    void inMemoryDateTruncation() {
        DateRangeResolver resolver = new DateRangeResolver(4);

        // 2026-03-31 is in FY 25-26 (started 2025-04-01)
        LocalDate March31 = LocalDate.of(2026, 3, 31);
        LocalDate fyStartMarch = resolver.fiscalYearStart(March31);
        assertEquals(LocalDate.of(2025, 4, 1), fyStartMarch);
        assertEquals("FY 25-26", BucketLabels.bucketLabel(fyStartMarch, Granularity.FY, 4));

        // 2026-04-01 is in FY 26-27 (started 2026-04-01)
        LocalDate April1 = LocalDate.of(2026, 4, 1);
        LocalDate fyStartApril = resolver.fiscalYearStart(April1);
        assertEquals(LocalDate.of(2026, 4, 1), fyStartApril);
        assertEquals("FY 26-27", BucketLabels.bucketLabel(fyStartApril, Granularity.FY, 4));
    }
}
