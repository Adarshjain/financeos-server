package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.financeos.core.time.AppTime;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartOrder;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonPeriod;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Top N + Other, breakdown and comparison on a computed (in-memory) datasource, e.g. positions. */
class InMemoryReportExecutorChartShapingTest {

    private InMemoryReportExecutor executor;
    private Ds ds;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Kolkata")));
        executor = new InMemoryReportExecutor(new DateRangeResolver(4));
        ds = new Ds();
        ds.rows = List.of(
                row("Stocks", "HDFC", "500", "2026-03-02"), row("Stocks", "TCS", "300", "2026-03-03"),
                row("Stocks", "TCS", "100", "2026-02-10"), row("Bonds", "GOI", "200", "2026-03-04"),
                row("Gold", "SGB", "50", "2026-03-05"), row("Cash", "FD", "20", "2026-03-06"),
                row(null, "X", "5", "2026-03-07"), row("Gold", "SGB", "70", "2026-02-12"),
                row("Bonds", "GOI", "30", "2026-02-15"), row("Crypto", "BTC", "9", "2026-02-20"));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static Map<String, Object> row(String cls, String name, String value, String date) {
        Map<String, Object> m = new HashMap<>();
        m.put("cls", cls);
        m.put("name", name);
        m.put("value", new BigDecimal(value));
        m.put("date", LocalDate.parse(date));
        return m;
    }

    private static final MeasureRef SUM = new MeasureRef("value", Aggregation.SUM);

    private ChartDefinition def(ChartType type, DimensionRef dim, Integer limit, Boolean other, ChartOrder order,
                                Comparison cmp, DimensionRef sub, Integer subLimit, FilterClause... filters) {
        return new ChartDefinition(type, dim, null, SUM, List.of(filters), limit, other, order, cmp, sub, subLimit);
    }

    private static final DimensionRef CLS = new DimensionRef("cls", null);

    @Test
    void limitWithOtherFoldsTheTailLastAndKeepsDimensionOrder() {
        ChartData data = executor.execute(def(ChartType.BAR, CLS, 2, true, null, null, null, null), ds, null);

        // totals: Stocks 900, Bonds 230, Gold 120, Crypto 9, Cash 20, (none) 5 -> top 2 = Stocks, Bonds
        assertEquals(List.of("Bonds", "Stocks", "Other"), data.categories());
        assertEquals(List.of(new BigDecimal("230"), new BigDecimal("900"), new BigDecimal("154")), data.series().get(0).data());
        assertEquals(2, data.other().index());
        assertEquals(4, data.other().foldedCount());
        assertNull(data.breakdown());
    }

    @Test
    void limitWithoutOtherDropsTheTail() {
        ChartData data = executor.execute(def(ChartType.BAR, CLS, 1, null, ChartOrder.VALUE_DESC, null, null, null), ds, null);
        assertEquals(List.of("Stocks"), data.categories());
        assertNull(data.other());
    }

    @Test
    void orderAscendingWithoutALimit() {
        ChartData data = executor.execute(def(ChartType.BAR, CLS, null, null, ChartOrder.VALUE_ASC, null, null, null), ds, null);
        assertEquals(List.of("(none)", "Crypto", "Cash", "Gold", "Bonds", "Stocks"), data.categories());
    }

    @Test
    void breakdownCountsAveragesAndListsTopSubRows() {
        ChartData data = executor.execute(def(ChartType.BREAKDOWN, CLS, 2, true, null, null, new DimensionRef("name", null), 1), ds, null);

        assertEquals(List.of("Stocks", "Bonds", "Other"), data.categories());
        ChartData.BreakdownRow stocks = data.breakdown().rows().get(0);
        assertEquals(3, stocks.count());
        assertEquals(new BigDecimal("300.0000"), stocks.avg());
        assertEquals(1, stocks.subRows().size());
        assertEquals("HDFC", stocks.subRows().get(0).label());
        assertEquals(new BigDecimal("500"), stocks.subRows().get(0).value());
        assertEquals(List.of(), data.breakdown().rows().get(2).subRows());
        assertEquals(new BigDecimal("1284"), data.breakdown().total());
    }

    @Test
    void comparisonReadsThePreviousMonthFromTheSameRows() {
        ChartData data = executor.execute(def(ChartType.BAR, CLS, 2, true, null,
                new Comparison(true, ComparisonPeriod.PREVIOUS_PERIOD, null), null, null,
                new FilterClause("date", "this_month", null)), ds, null);

        // March: Stocks 900-100=800, Bonds 200, Gold 50, Cash 20, (none) 5 -> top 2 Bonds, Stocks
        assertEquals(List.of("Bonds", "Stocks", "Other"), data.categories());
        assertEquals(List.of(new BigDecimal("30"), new BigDecimal("100"), new BigDecimal("79")),
                data.previous().series().get(0).data()); // Feb: Gold 70 + Crypto 9
        assertEquals(LocalDate.of(2026, 2, 1), data.previous().dateRange().from());
        assertEquals(LocalDate.of(2026, 2, 28), data.previous().dateRange().to());
    }

    @Test
    void monthBucketsGetCategoryRanges() {
        ChartData data = executor.execute(def(ChartType.BAR, new DimensionRef("date", Granularity.MONTH), null, null, null,
                null, null, null), ds, null);
        assertEquals(List.of("Feb 26", "Mar 26"), data.categories());
        assertEquals(LocalDate.of(2026, 2, 28), data.categoryRanges().get(0).to());
        assertEquals(LocalDate.of(2026, 3, 1), data.categoryRanges().get(1).from());
    }

    private static class Ds implements ComputedReportDatasource {
        List<Map<String, Object>> rows = new ArrayList<>();

        @Override public String name() { return "shaping"; }
        @Override public String label() { return "Shaping"; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("cls", "Class", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("name", "Name", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("value", "Value", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM, Aggregation.COUNT, Aggregation.AVG), null, null, List.of(), "currency"),
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of()));
        }

        @Override
        public List<Map<String, Object>> rows() {
            return rows;
        }
    }
}
