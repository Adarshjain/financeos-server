package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonPeriod;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The SQL chart: breakdown extras, top N + Other, previous-period comparison, bucket ranges (mock EntityManager). */
class ChartReportExecutorShapingTest {

    private final UUID userId = UUID.randomUUID();
    private ChartReportExecutor executor;
    private ReportDatasource ds;
    private final List<String> sqls = new ArrayList<>();
    private final List<Map<String, Object>> paramSets = new ArrayList<>();
    private final Deque<List<Object[]>> grouped = new ArrayDeque<>();
    private final Deque<List<Object[]>> subRows = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Kolkata")));
        BillingCycleService cycles = mock(BillingCycleService.class);
        DateRangeResolver drr = new DateRangeResolver(4);
        ds = new TransactionsDatasource(new SqlPredicates(drr), drr, cycles);
        executor = new ChartReportExecutor(drr, cycles);
        EntityManager em = mock(EntityManager.class);
        ReflectionTestUtils.setField(executor, "em", em);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            sqls.add(sql);
            Map<String, Object> params = new HashMap<>();
            paramSets.add(params);
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenAnswer(pi -> {
                params.put(pi.getArgument(0), pi.getArgument(1));
                return q;
            });
            when(q.getSingleResult()).thenReturn(42L);
            if (sql.contains("sub_key")) {
                when(q.getResultList()).thenReturn(subRows.poll());
            } else if (!sql.contains("COUNT(DISTINCT")) {
                when(q.getResultList()).thenReturn(grouped.poll());
            }
            return q;
        });
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static List<Object[]> rows(Object[]... rows) {
        return java.util.Arrays.asList(rows);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** dim, val, cnt, mcnt, avg */
    private static Object[] g(Object dim, String val, long cnt, String avg) {
        return new Object[]{dim, d(val), cnt, cnt, d(avg)};
    }

    private static Comparison comparison() {
        return new Comparison(true, ComparisonPeriod.PREVIOUS_PERIOD, false);
    }

    private ChartDefinition def(ChartType type, DimensionRef dim, DimensionRef series, Integer limit, Boolean other,
                                Comparison cmp, DimensionRef sub, Integer subLimit, FilterClause... filters) {
        return new ChartDefinition(type, dim, series, new MeasureRef("amount", Aggregation.SUM), List.of(filters),
                limit, other, null, cmp, sub, subLimit);
    }

    private static final DimensionRef CATEGORY = new DimensionRef("category", null);
    private static final DimensionRef ACCOUNT = new DimensionRef("account", null);

    @Test
    void breakdownReturnsCountAverageSubRowsAndFoldsTheTail() {
        grouped.add(rows(g("Dining", "300", 3, "100"), g("Fuel", "100", 2, "50"), g("Rent", "900", 1, "900"),
                g("Travel", "40", 1, "40"), g("Misc", "10", 2, "5")));
        subRows.add(rows(
                new Object[]{"Dining", "HDFC", d("200"), 2L}, new Object[]{"Dining", "SBI", d("60"), 1L},
                new Object[]{"Dining", "Cash", d("40"), 1L}, new Object[]{"Dining", "Amex", d("1"), 1L},
                new Object[]{"Rent", "HDFC", d("900"), 1L}, new Object[]{"Fuel", "HDFC", d("100"), 2L}));

        ChartData data = executor.execute(def(ChartType.BREAKDOWN, CATEGORY, new DimensionRef("type", null), 3, true, null,
                ACCOUNT, null), ds, userId);

        assertEquals("breakdown", data.chartType());
        assertEquals(List.of("Rent", "Dining", "Fuel", "Other"), data.categories()); // default order value_desc, Other last
        assertEquals(1, data.series().size()); // series ignored
        assertEquals(List.of(d("900"), d("300"), d("100"), d("50")), data.series().get(0).data());
        assertEquals(3, data.other().index());
        assertEquals(2, data.other().foldedCount());
        ChartData.Breakdown bd = data.breakdown();
        assertEquals(d("1350"), bd.total());
        assertNull(bd.previousTotal());
        ChartData.BreakdownRow dining = bd.rows().get(1);
        assertEquals(3, dining.count());
        assertEquals(d("100.0000"), dining.avg());
        assertNull(dining.previousCount());
        assertEquals(List.of("HDFC", "SBI", "Cash"), dining.subRows().stream().map(ChartData.SubRow::label).toList());
        assertEquals(2, dining.subRows().get(0).count());
        ChartData.BreakdownRow other = bd.rows().get(3);
        assertEquals(3, other.count()); // Travel + Misc
        assertEquals(d("16.6667"), other.avg()); // (40*1 + 5*2) / 3, weighted by measure count
        assertEquals(List.of(), other.subRows());
        assertNull(data.previous());
        assertNull(data.categoryRanges());
    }

    @Test
    void breakdownRunsOneSubQueryNoMatterHowManyCategories() {
        grouped.add(rows(g("A", "1", 1, "1"), g("B", "2", 1, "2"), g("C", "3", 1, "3"), g("D", "4", 1, "4")));
        subRows.add(rows());
        executor.execute(def(ChartType.BREAKDOWN, CATEGORY, null, null, null, null, ACCOUNT, 2), ds, userId);

        assertEquals(1, sqls.stream().filter(s -> s.contains("sub_key")).count());
        assertEquals(1, sqls.stream().filter(s -> s.contains("COUNT(*) AS cnt, COUNT(") && !s.contains("sub_key")).count());
        assertFalse(sqls.stream().anyMatch(s -> s.contains("series_key")));
        assertTrue(sqls.stream().filter(s -> s.contains("sub_key")).findFirst().orElseThrow().contains("GROUP BY"));
    }

    @Test
    void topNPlusOtherOnAPlainBarWithASeries() {
        grouped.add(rows(
                new Object[]{"A", "DEBIT", d("10"), 1L, 1L, d("10")}, new Object[]{"A", "CREDIT", d("5"), 1L, 1L, d("5")},
                new Object[]{"B", "DEBIT", d("30"), 1L, 1L, d("30")},
                new Object[]{"C", "CREDIT", d("2"), 1L, 1L, d("2")}, new Object[]{"D", "DEBIT", d("1"), 1L, 1L, d("1")}));

        ChartData data = executor.execute(def(ChartType.STACKED_BAR, CATEGORY, new DimensionRef("type", null), 2, true, null,
                null, null), ds, userId);

        assertEquals(List.of("A", "B", "Other"), data.categories());
        assertEquals(List.of("DEBIT", "CREDIT"), data.series().stream().map(ChartData.Series::name).toList());
        assertEquals(List.of(d("10"), d("30"), d("1")), data.series().get(0).data());
        assertEquals(List.of(d("5"), d("0"), d("2")), data.series().get(1).data());
        assertNull(data.breakdown());
    }

    @Test
    void comparisonRunsThePreviousMonthAndAlignsByCategory() {
        grouped.add(rows(g("Dining", "300", 3, "100"), g("Fuel", "100", 2, "50"), g("Rent", "900", 1, "900")));
        grouped.add(rows(g("Dining", "250", 2, "125"), g("Rent", "900", 1, "900"), g("Gym", "30", 1, "30")));

        ChartData data = executor.execute(def(ChartType.BAR, CATEGORY, null, 2, true, comparison(), null, null,
                new FilterClause("date", "this_month", null)), ds, userId);

        assertEquals(List.of("Dining", "Rent", "Other"), data.categories());
        assertEquals(List.of(d("250"), d("900"), d("30")), data.previous().series().get(0).data()); // Other = Fuel(absent)+Gym
        assertEquals("amount", data.previous().series().get(0).name());
        assertEquals(LocalDate.of(2026, 2, 1), data.previous().dateRange().from());
        assertEquals(LocalDate.of(2026, 2, 28), data.previous().dateRange().to());
        assertEquals(LocalDate.of(2026, 3, 1), data.meta().dateRange().from());
        long groupedQueries = sqls.stream().filter(s -> s.contains(" AS cnt")).count();
        assertEquals(2, groupedQueries);
        assertTrue(paramSets.stream().anyMatch(p -> p.containsValue(LocalDate.of(2026, 2, 1))), "previous month bound is queried");
    }

    @Test
    void comparisonOnALastXDaysFilterShiftsByTheWindowLength() {
        grouped.add(rows(g("Dining", "300", 3, "100")));
        grouped.add(rows(g("Dining", "200", 2, "100")));
        ObjectNode seven = JsonNodeFactory.instance.objectNode();
        seven.put("amount", 7);

        ChartData data = executor.execute(def(ChartType.BAR, CATEGORY, null, null, null, comparison(), null, null,
                new FilterClause("date", "last_x_days", seven)), ds, userId);

        assertEquals(LocalDate.of(2026, 2, 26), data.previous().dateRange().from());
        assertEquals(LocalDate.of(2026, 3, 4), data.previous().dateRange().to());
        assertEquals(List.of(d("200")), data.previous().series().get(0).data());
    }

    @Test
    void comparisonWithoutADateFilterCarriesNoPreviousValuesAndRunsOneQuery() {
        grouped.add(rows(g("Dining", "300", 3, "100")));
        ChartData data = executor.execute(def(ChartType.BAR, CATEGORY, null, null, null, comparison(), null, null), ds, userId);

        assertNull(data.previous());
        assertEquals(1, sqls.stream().filter(s -> s.contains(" AS cnt")).count());
    }

    @Test
    void aDisabledComparisonIsIgnored() {
        grouped.add(rows(g("Dining", "300", 3, "100")));
        ChartData data = executor.execute(def(ChartType.BAR, CATEGORY, null, null, null,
                new Comparison(false, ComparisonPeriod.PREVIOUS_PERIOD, null), null, null,
                new FilterClause("date", "this_month", null)), ds, userId);
        assertNull(data.previous());
        assertEquals(1, sqls.stream().filter(s -> s.contains(" AS cnt")).count());
    }

    @Test
    void breakdownComparisonFillsPreviousCountAndTotal() {
        grouped.add(rows(g("Dining", "300", 3, "100"), g("Rent", "900", 1, "900")));
        grouped.add(rows(g("Dining", "250", 2, "125"), g("Gym", "30", 1, "30")));
        subRows.add(rows());

        ChartData data = executor.execute(def(ChartType.BREAKDOWN, CATEGORY, null, 1, true, comparison(), ACCOUNT, null,
                new FilterClause("date", "this_month", null)), ds, userId);

        assertEquals(List.of("Rent", "Other"), data.categories());
        ChartData.Breakdown bd = data.breakdown();
        assertEquals(0L, bd.rows().get(0).previousCount());
        assertEquals(3L, bd.rows().get(1).previousCount()); // Dining + Gym were not kept
        assertEquals(d("1200"), bd.total());
        assertEquals(d("280"), bd.previousTotal());
    }

    @Test
    void monthBucketsCarryTheirExactDateRanges() {
        grouped.add(rows(
                new Object[]{java.sql.Date.valueOf("2026-01-01"), d("10"), 1L, 1L, d("10")},
                new Object[]{java.sql.Date.valueOf("2026-02-01"), d("20"), 1L, 1L, d("20")},
                new Object[]{null, d("5"), 1L, 1L, d("5")}));

        ChartData data = executor.execute(def(ChartType.BAR, new DimensionRef("date", Granularity.MONTH), null, null, null,
                null, null, null), ds, userId);

        assertEquals(List.of("Jan 26", "Feb 26", "(none)"), data.categories());
        assertEquals(3, data.categoryRanges().size());
        assertEquals(LocalDate.of(2026, 1, 1), data.categoryRanges().get(0).from());
        assertEquals(LocalDate.of(2026, 1, 31), data.categoryRanges().get(0).to());
        assertEquals(LocalDate.of(2026, 2, 28), data.categoryRanges().get(1).to());
        assertNull(data.categoryRanges().get(2));
    }

    @Test
    void aPlainChartIsUnchangedAndCarriesNoNewFields() {
        grouped.add(rows(g("Dining", "300", 3, "100"), g("Fuel", "100", 2, "50")));
        ChartData data = executor.execute(def(ChartType.BAR, CATEGORY, null, null, null, null, null, null), ds, userId);

        assertEquals(List.of("Dining", "Fuel"), data.categories());
        assertNull(data.previous());
        assertNull(data.other());
        assertNull(data.breakdown());
        assertNull(data.categoryRanges());
        assertEquals(42L, data.meta().rowCount());
    }

    @Test
    void everyQueryBindsTheUserId() {
        grouped.add(rows(g("Dining", "300", 3, "100")));
        grouped.add(rows(g("Dining", "250", 2, "125")));
        subRows.add(rows());

        executor.execute(def(ChartType.BREAKDOWN, CATEGORY, null, null, null, comparison(), ACCOUNT, null,
                new FilterClause("date", "this_month", null)), ds, userId);

        // current count + current grouped + previous grouped + sub-rows
        assertEquals(4, sqls.size());
        for (int i = 0; i < sqls.size(); i++) {
            assertEquals(userId.toString(), paramSets.get(i).get("userId"), "query " + i + ": " + sqls.get(i));
        }
        assertEquals(1, sqls.stream().filter(s -> s.contains("sub_key")).count());
    }
}
