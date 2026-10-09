package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Billing-cycle filters on computed (in-memory) datasources. */
class InMemoryReportExecutorCycleTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID cardA = UUID.randomUUID();
    private final UUID cardB = UUID.randomUUID();

    private BillingCycleService cycles;
    private InMemoryReportExecutor executor;
    private CycleDatasource ds;

    @BeforeEach
    void setUp() {
        cycles = mock(BillingCycleService.class);
        executor = new InMemoryReportExecutor(new DateRangeResolver(4), cycles);
        // Forced by the KPI comparison now being on by default: it reads the previous cycle's windows,
        // which the real service returns empty (never null) when a test does not stub them.
        when(cycles.windows(any(), anyInt(), any(), any())).thenReturn(new CycleWindows(Map.of()));
        ds = new CycleDatasource("cardId");
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private Map<String, Object> row(Object card, LocalDate date, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("cardId", card);
        m.put("date", date);
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    private void windows(int ago, Object... cardStartEnd) {
        Map<UUID, Cycle> m = new LinkedHashMap<>();
        for (int i = 0; i < cardStartEnd.length; i += 3) {
            m.put((UUID) cardStartEnd[i], new Cycle((LocalDate) cardStartEnd[i + 1], (LocalDate) cardStartEnd[i + 2], Source.PROJECTED));
        }
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), any())).thenReturn(new CycleWindows(m));
    }

    private static FilterClause cycle(String op) {
        return new FilterClause("date", op, null);
    }

    private KpiDefinition kpi(Comparison c, FilterClause... f) {
        return new KpiDefinition("amount", Aggregation.SUM, List.of(f), c);
    }

    // ---- row matching ----

    @Test
    void rowsMatchTheirOwnCardsWindow() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4), cardB, d(2026, 2, 20), d(2026, 3, 19));
        ds.rows = List.of(
                row(cardA, d(2026, 2, 10), "1"),     // in A
                row(cardA, d(2026, 3, 10), "10"),    // after A's window (but in B's) -> out
                row(cardB, d(2026, 3, 10), "100"),   // in B
                row(cardB, d(2026, 2, 10), "1000")); // before B's window -> out

        var data = executor.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of());

        assertEquals(new BigDecimal("101"), data.value());
        assertEquals(2, data.meta().rowCount());
    }

    @Test
    void windowBoundariesAreInclusive() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        ds.rows = List.of(row(cardA, d(2026, 2, 5), "1"), row(cardA, d(2026, 3, 4), "10"),
                row(cardA, d(2026, 2, 4), "100"), row(cardA, d(2026, 3, 5), "1000"));

        assertEquals(new BigDecimal("11"), executor.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of()).value());
    }

    @Test
    void nonCardUnknownNullAndUnparsableKeysMatchNothing() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        ds.rows = List.of(
                row(UUID.randomUUID(), d(2026, 2, 10), "1"), // not a card of the user
                row(null, d(2026, 2, 10), "10"),             // no key value
                row("not-a-uuid", d(2026, 2, 10), "100"),    // unparsable
                row(cardA, null, "1000"));                   // no date

        assertEquals(BigDecimal.ZERO, executor.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of()).value());
    }

    @Test
    void stringCardIdsAreParsed() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        ds.rows = List.of(row(cardA.toString(), d(2026, 2, 10), "7"));

        assertEquals(new BigDecimal("7"), executor.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of()).value());
    }

    @Test
    void datasourceWithoutBillingCycleAccountFieldMatchesNothing() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        CycleDatasource noKey = new CycleDatasource(null);
        noKey.rows = List.of(row(cardA, d(2026, 2, 10), "7"));

        assertEquals(BigDecimal.ZERO, executor.execute(kpi(null, cycle("this_billing_cycle")), noKey, Map.of()).value());
    }

    @Test
    void withoutBillingCycleServiceCycleFiltersMatchNothing() {
        InMemoryReportExecutor plain = new InMemoryReportExecutor(new DateRangeResolver(4));
        ds.rows = List.of(row(cardA, d(2026, 2, 10), "7"));

        var data = plain.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of());

        assertEquals(BigDecimal.ZERO, data.value());
        assertNull(data.meta().dateRange());
    }

    @Test
    void previousBillingCycleUsesTheCycleBefore() {
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        ds.rows = List.of(row(cardA, d(2026, 1, 10), "3"), row(cardA, d(2026, 2, 10), "30"));

        assertEquals(new BigDecimal("3"), executor.execute(kpi(null, cycle("previous_billing_cycle")), ds, Map.of()).value());
    }

    @Test
    void otherFiltersStillApplyAlongsideTheCycle() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        Map<String, Object> keep = row(cardA, d(2026, 2, 10), "5");
        keep.put("kind", "x");
        Map<String, Object> drop = row(cardA, d(2026, 2, 11), "50");
        drop.put("kind", "y");
        ds.rows = List.of(keep, drop);

        var data = executor.execute(kpi(null, cycle("this_billing_cycle"),
                new FilterClause("kind", "exact", com.fasterxml.jackson.databind.node.TextNode.valueOf("x"))), ds, Map.of());

        assertEquals(new BigDecimal("5"), data.value());
    }

    // ---- DateHint ----

    @Test
    void hintSpansCurrentWindowsOnly_whenNotComparing() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4), cardB, d(2026, 2, 20), d(2026, 3, 19));
        executor.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of());

        assertEquals(1, ds.hints.size());
        assertEquals("date", ds.hints.get(0).field());
        assertEquals(d(2026, 2, 5), ds.hints.get(0).from());
        assertEquals(d(2026, 3, 19), ds.hints.get(0).to());
    }

    @Test
    void hintCoversCurrentAndPreviousWindowsWhenComparing() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        executor.execute(kpi(new Comparison(true, null, null), cycle("this_billing_cycle")), ds, Map.of());

        assertEquals(d(2026, 1, 5), ds.hints.get(0).from());
        assertEquals(d(2026, 3, 4), ds.hints.get(0).to());
    }

    @Test
    void hintIsNullWhenUserHasNoCards() {
        windows(0);
        executor.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of());

        assertEquals(1, ds.hints.size());
        assertNull(ds.hints.get(0));
    }

    @Test
    void chartWithCycleFilterLoadsRowsWithSpanHint() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        ds.rows = List.of(row(cardA, d(2026, 2, 10), "4"), row(cardA, d(2026, 5, 10), "40"));

        var chart = executor.execute(new ChartDefinition(ChartType.BAR, new DimensionRef("cardId", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of(cycle("this_billing_cycle"))), ds, Map.of());

        assertEquals(List.of(new BigDecimal("4")), chart.series().get(0).data());
        assertEquals(d(2026, 2, 5), ds.hints.get(0).from());
        assertEquals(d(2026, 3, 4), ds.hints.get(0).to());
    }

    // ---- KPI comparison ----

    @Test
    void comparisonUsesThePreviousCyclePerCard() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4), cardB, d(2026, 2, 20), d(2026, 3, 19));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4), cardB, d(2026, 1, 20), d(2026, 2, 19));
        ds.rows = List.of(
                row(cardA, d(2026, 2, 10), "100"),   // current A
                row(cardB, d(2026, 3, 10), "100"),   // current B
                row(cardA, d(2026, 1, 10), "50"),    // previous A
                row(cardB, d(2026, 2, 10), "50"),    // previous B (also a date in A's current window: must NOT count for A)
                row(cardA, d(2026, 2, 25), "1000")); // A: not previous (A prev ends Feb 4), not current? Feb 25 in A current -> counts to current

        var data = executor.execute(kpi(new Comparison(true, null, null), cycle("this_billing_cycle")), ds, Map.of());

        assertEquals(new BigDecimal("1200"), data.value());
        assertNotNull(data.comparison());
        assertEquals(new BigDecimal("100"), data.comparison().previousValue());
        assertEquals(new BigDecimal("1100"), data.comparison().change());
        assertEquals("up", data.comparison().direction());
        assertEquals(d(2026, 1, 5), data.comparison().previousDateRange().from());
        assertEquals(d(2026, 2, 19), data.comparison().previousDateRange().to());
        assertEquals(d(2026, 2, 5), data.meta().dateRange().from());
        assertEquals(d(2026, 3, 19), data.meta().dateRange().to());
    }

    @Test
    void comparisonSentimentFollowsHigherIsBetter() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        ds.rows = List.of(row(cardA, d(2026, 2, 10), "10"), row(cardA, d(2026, 1, 10), "50"));

        var data = executor.execute(kpi(new Comparison(true, null, true), cycle("this_billing_cycle")), ds, Map.of());

        assertEquals("down", data.comparison().direction());
        assertEquals("bad", data.comparison().sentiment());
        assertEquals(new BigDecimal("-80.0000"), data.comparison().changePercent());
    }

    @Test
    void flatComparisonHasFlatDirectionAndNeutralSentiment() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        ds.rows = List.of(row(cardA, d(2026, 2, 10), "10"), row(cardA, d(2026, 1, 10), "10"));

        var data = executor.execute(kpi(new Comparison(true, null, true), cycle("this_billing_cycle")), ds, Map.of());

        assertEquals("flat", data.comparison().direction());
        assertEquals("neutral", data.comparison().sentiment());
    }

    @Test
    void zeroPreviousValueHasNoPercentChange() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        ds.rows = List.of(row(cardA, d(2026, 2, 10), "10"));

        var data = executor.execute(kpi(new Comparison(true, null, null), cycle("this_billing_cycle")), ds, Map.of());

        assertEquals(BigDecimal.ZERO, data.comparison().previousValue());
        assertNull(data.comparison().changePercent());
    }

    @Test
    void inMemoryComparisonIsOnUnlessExplicitlyDisabled() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        ds.rows = List.of(row(cardA, d(2026, 2, 10), "10"), row(cardA, d(2026, 1, 10), "4"));

        assertNull(executor.execute(kpi(new Comparison(false, null, null), cycle("this_billing_cycle")), ds, Map.of()).comparison());
        verify(cycles, never()).windows(eq(userId), eq(1), any(LocalDate.class), any());

        var noComparison = executor.execute(kpi(null, cycle("this_billing_cycle")), ds, Map.of()).comparison();
        assertEquals(new BigDecimal("4"), noComparison.previousValue());
        assertEquals(d(2026, 1, 5), noComparison.previousDateRange().from());
        assertEquals("neutral", noComparison.sentiment());
        var nullEnabled = executor.execute(kpi(new Comparison(null, null, null), cycle("this_billing_cycle")), ds, Map.of()).comparison();
        assertEquals(new BigDecimal("4"), nullEnabled.previousValue());
    }

    @Test
    void noComparisonWhenUserHasNoCards() {
        windows(0);
        var data = executor.execute(kpi(new Comparison(true, null, null), cycle("this_billing_cycle")), ds, Map.of());

        assertNull(data.comparison());
        assertNull(data.meta().dateRange());
        verify(cycles, never()).windows(any(), eq(1), any(), any());
    }

    @Test
    void previousCycleFilterComparesWithTwoCyclesAgo() {
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        windows(2, cardA, d(2025, 12, 5), d(2026, 1, 4));
        ds.rows = List.of(row(cardA, d(2026, 1, 10), "10"), row(cardA, d(2025, 12, 10), "4"));

        var data = executor.execute(kpi(new Comparison(true, null, null), cycle("previous_billing_cycle")), ds, Map.of());

        assertEquals(new BigDecimal("10"), data.value());
        assertEquals(new BigDecimal("4"), data.comparison().previousValue());
    }

    // ---- datasource ----

    private static class CycleDatasource implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();
        final List<DateHint> hints = new ArrayList<>();
        private final String key;

        /** @param key the id key the card field reads from the row; null = datasource without billing cycles */
        CycleDatasource(String key) {
            this.key = key;
        }

        @Override public String name() { return "cyc"; }
        @Override public String label() { return "Cyc"; }
        @Override public String billingCycleAccountField() { return key == null ? null : "card"; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    FieldDef.cycleDate("date", "Date", List.of()),
                    new FieldDef("cardId", "Card", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, List.of(), null, key),
                    new FieldDef("kind", "Kind", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM, Aggregation.COUNT), null, null, List.of(), "currency"));
        }

        @Override public List<Map<String, Object>> rows() { return rows; }

        @Override
        public List<Map<String, Object>> rows(DateHint hint) {
            hints.add(hint);
            return rows;
        }
    }
}
