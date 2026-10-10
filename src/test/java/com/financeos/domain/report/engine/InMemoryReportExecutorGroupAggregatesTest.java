package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.Aggregation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group totals of a KPI's underlying rows: each group (rows sharing the group field's value)
 * aggregates the measure exactly as the KPI's own figure does, keyed by the raw group value in
 * first-seen order; rows without a group value belong to no group.
 */
class InMemoryReportExecutorGroupAggregatesTest {

    private final InMemoryReportExecutor executor = new InMemoryReportExecutor(new DateRangeResolver(4));

    private static Map<String, Object> row(String side, String value) {
        Map<String, Object> row = new HashMap<>();
        row.put("side", side);
        row.put("signedValue", value == null ? null : new BigDecimal(value));
        return row;
    }

    private final List<Map<String, Object>> rows = List.of(
            row("liability", "-500"),
            row("asset", "10000"),
            row("asset", "6000"),
            row("liability", "-250.50"),
            row(null, "999"),
            row("asset", null));

    @Test
    void sumTotalsEachGroupKeyedByRawValueInFirstSeenOrder() {
        Map<String, BigDecimal> totals = executor.groupAggregates(rows, "side", "signedValue", Aggregation.SUM);

        assertEquals(List.of("liability", "asset"), List.copyOf(totals.keySet()));
        assertEquals(0, new BigDecimal("-750.50").compareTo(totals.get("liability")));
        assertEquals(0, new BigDecimal("16000").compareTo(totals.get("asset")));
    }

    @Test
    void countCountsOnlyRowsThatHaveTheMeasure() {
        Map<String, BigDecimal> totals = executor.groupAggregates(rows, "side", "signedValue", Aggregation.COUNT);

        assertEquals(0, new BigDecimal("2").compareTo(totals.get("asset")));
        assertEquals(0, new BigDecimal("2").compareTo(totals.get("liability")));
    }

    @Test
    void avgMinAndMaxAggregateWithinEachGroup() {
        assertEquals(0, new BigDecimal("8000").compareTo(
                executor.groupAggregates(rows, "side", "signedValue", Aggregation.AVG).get("asset")));
        assertEquals(0, new BigDecimal("6000").compareTo(
                executor.groupAggregates(rows, "side", "signedValue", Aggregation.MIN).get("asset")));
        assertEquals(0, new BigDecimal("-250.50").compareTo(
                executor.groupAggregates(rows, "side", "signedValue", Aggregation.MAX).get("liability")));
    }

    @Test
    void aGroupWhoseRowsHaveNoMeasureIsZeroForSumAndLeftOutForAvg() {
        List<Map<String, Object>> onlyMissing = List.of(row("asset", null));

        assertEquals(0, BigDecimal.ZERO.compareTo(
                executor.groupAggregates(onlyMissing, "side", "signedValue", Aggregation.SUM).get("asset")));
        assertTrue(executor.groupAggregates(onlyMissing, "side", "signedValue", Aggregation.AVG).isEmpty());
    }

    @Test
    void rowsWithoutAGroupValueAndNoRowsGiveNoGroups() {
        assertTrue(executor.groupAggregates(List.of(row(null, "5")), "side", "signedValue", Aggregation.SUM).isEmpty());
        assertTrue(executor.groupAggregates(List.of(), "side", "signedValue", Aggregation.SUM).isEmpty());
    }
}
