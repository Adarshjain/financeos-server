package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@link KpiPeriodResolver}: the current and previous period of a KPI, shared by both KPI executors. */
class KpiPeriodResolverTest {

    /** 2026-03-10T20:00Z is already 2026-03-11 in IST, the business date. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 11);

    private final UUID userId = UUID.randomUUID();
    private final UUID card = UUID.randomUUID();
    private final ReportDatasource ds = new PeriodDatasource();
    private BillingCycleService cycles;
    private KpiPeriodResolver resolver;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Kolkata")));
        cycles = mock(BillingCycleService.class);
        resolver = new KpiPeriodResolver(new DateRangeResolver(4), cycles);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private static KpiDefinition kpi(Comparison comparison, FilterClause... filters) {
        return new KpiDefinition("amount", Aggregation.SUM, List.of(filters), comparison);
    }

    private static FilterClause date(String field, String operator) {
        return new FilterClause(field, operator, null);
    }

    private static FilterClause between(String field, String from, String to) {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("from", from).put("to", to);
        return new FilterClause(field, "between", value);
    }

    private static FilterClause typeIsDebit() {
        return new FilterClause("type", "is", TextNode.valueOf("DEBIT"));
    }

    private static FilterClause accountIs(String ref) {
        return new FilterClause("account", "is", TextNode.valueOf(ref));
    }

    private void windows(int ago, String ref, LocalDate start, LocalDate end) {
        Map<UUID, Cycle> byAccount = new LinkedHashMap<>();
        if (start != null) {
            byAccount.put(card, new Cycle(start, end, Source.PROJECTED));
        }
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), eq(ref))).thenReturn(new CycleWindows(byAccount));
    }

    private static void assertBetween(FilterClause filter, String field, LocalDate from, LocalDate to) {
        assertEquals(field, filter.field());
        assertEquals("between", filter.operator());
        assertEquals(from.toString(), filter.value().get("from").asText());
        assertEquals(to.toString(), filter.value().get("to").asText());
    }

    // ---- no date filter ----

    @Test
    void withoutADateFilterTheCurrentPeriodIsUnboundedAndThereIsNoPrevious() {
        FilterClause type = typeIsDebit();
        KpiDefinition def = kpi(null, type);

        KpiPeriods periods = resolver.resolve(def, ds, userId);

        assertNull(periods.dateFilter());
        assertSame(def.filters(), periods.current().filters());
        assertFalse(periods.current().range().bounded());
        assertFalse(periods.previousAvailable());
        verifyNoInteractions(cycles);
    }

    @Test
    void nullFiltersResolveToNone() {
        KpiPeriods periods = resolver.resolve(new KpiDefinition("amount", Aggregation.SUM, null, null), ds, userId);

        assertEquals(List.of(), periods.current().filters());
        assertFalse(periods.previousAvailable());
    }

    // ---- date operators ----

    @Test
    void aCalendarMonthComparesWithTheMonthBeforeAndKeepsTheOtherFilters() {
        FilterClause type = typeIsDebit();
        FilterClause month = date("date", "this_month");
        KpiDefinition def = kpi(null, month, type);

        KpiPeriods periods = resolver.resolve(def, ds, userId);

        assertSame(month, periods.dateFilter());
        assertSame(def.filters(), periods.current().filters());
        assertEquals(DateRange.of(d(2026, 3, 1), d(2026, 3, 31)), periods.current().range());
        assertEquals(DateRange.of(d(2026, 2, 1), d(2026, 2, 28)), periods.previous().range());
        List<FilterClause> previous = periods.previous().filters();
        assertEquals(2, previous.size());
        assertSame(type, previous.get(0));
        assertBetween(previous.get(1), "date", d(2026, 2, 1), d(2026, 2, 28));
    }

    @Test
    void anExplicitRangeComparesWithTheEqualLengthWindowBefore() {
        KpiPeriods periods = resolver.resolve(kpi(null, between("date", "2026-05-01", "2026-05-31")), ds, userId);

        assertEquals(DateRange.of(d(2026, 5, 1), d(2026, 5, 31)), periods.current().range());
        assertEquals(DateRange.of(d(2026, 3, 31), d(2026, 4, 30)), periods.previous().range());
        assertBetween(periods.previous().filters().get(0), "date", d(2026, 3, 31), d(2026, 4, 30));
    }

    @Test
    void comparisonIsOnUnlessExplicitlyDisabled() {
        FilterClause month = date("date", "this_month");

        assertTrue(resolver.resolve(kpi(null, month), ds, userId).previousAvailable());
        assertTrue(resolver.resolve(kpi(new Comparison(null, null, null), month), ds, userId).previousAvailable());
        assertTrue(resolver.resolve(kpi(new Comparison(true, null, null), month), ds, userId).previousAvailable());

        KpiPeriods disabled = resolver.resolve(kpi(new Comparison(false, null, null), month), ds, userId);
        assertFalse(disabled.previousAvailable());
        assertEquals(DateRange.of(d(2026, 3, 1), d(2026, 3, 31)), disabled.current().range());
    }

    @Test
    void openEndedDateFiltersHaveNoRangeAndNoPrevious() {
        for (FilterClause open : List.of(date("date", "all_time"),
                new FilterClause("date", "after", TextNode.valueOf("2026-01-01")),
                new FilterClause("date", "before", TextNode.valueOf("2026-01-01")))) {
            KpiPeriods periods = resolver.resolve(kpi(null, open), ds, userId);

            assertSame(open, periods.dateFilter());
            assertFalse(periods.current().range().bounded());
            assertFalse(periods.previousAvailable());
        }
    }

    @Test
    void onlyTheFirstDateFilterDefinesAndShiftsThePeriod() {
        FilterClause settled = between("settlementDate", "2026-05-01", "2026-05-31");
        FilterClause booked = between("date", "2026-01-01", "2026-12-31");

        KpiPeriods periods = resolver.resolve(kpi(null, settled, booked), ds, userId);

        assertSame(settled, periods.dateFilter());
        assertEquals(DateRange.of(d(2026, 5, 1), d(2026, 5, 31)), periods.current().range());
        List<FilterClause> previous = periods.previous().filters();
        assertSame(booked, previous.get(0));
        assertBetween(previous.get(1), "settlementDate", d(2026, 3, 31), d(2026, 4, 30));
    }

    // ---- billing cycles ----

    @Test
    void thisCycleComparesWithTheAccountsCycleBefore() {
        windows(0, "HDFC", d(2026, 3, 5), d(2026, 4, 4));
        windows(1, "HDFC", d(2026, 2, 5), d(2026, 3, 4));
        FilterClause account = accountIs("HDFC");
        FilterClause cycle = date("date", CycleOperators.THIS_CYCLE);

        KpiPeriods periods = resolver.resolve(kpi(null, cycle, account), ds, userId);

        assertSame(cycle, periods.dateFilter());
        assertEquals(DateRange.of(d(2026, 3, 5), d(2026, 4, 4)), periods.current().range());
        assertEquals(DateRange.of(d(2026, 2, 5), d(2026, 3, 4)), periods.previous().range());
        List<FilterClause> previous = periods.previous().filters();
        assertSame(account, previous.get(0));
        assertEquals(CycleOperators.CYCLES_AGO, previous.get(1).operator());
        assertEquals(1, previous.get(1).value().get("amount").asInt());
        verify(cycles).windows(userId, 0, TODAY, "HDFC");
        verify(cycles).windows(userId, 1, TODAY, "HDFC");
    }

    @Test
    void previousCycleComparesWithTwoCyclesAgo() {
        windows(1, "HDFC", d(2026, 2, 5), d(2026, 3, 4));
        windows(2, "HDFC", d(2026, 1, 5), d(2026, 2, 4));

        KpiPeriods periods = resolver.resolve(
                kpi(null, date("date", CycleOperators.PREVIOUS_CYCLE), accountIs("HDFC")), ds, userId);

        assertEquals(DateRange.of(d(2026, 2, 5), d(2026, 3, 4)), periods.current().range());
        assertEquals(DateRange.of(d(2026, 1, 5), d(2026, 2, 4)), periods.previous().range());
        assertEquals(2, periods.previous().filters().get(1).value().get("amount").asInt());
    }

    @Test
    void anAccountWithoutCyclesHasNoRangeAndNeverAsksForThePreviousCycle() {
        windows(0, "nobody", null, null);

        KpiPeriods periods = resolver.resolve(
                kpi(null, date("date", CycleOperators.THIS_CYCLE), accountIs("nobody")), ds, userId);

        assertFalse(periods.current().range().bounded());
        assertFalse(periods.previousAvailable());
        verify(cycles, never()).windows(any(), eq(1), any(), any());
    }

    @Test
    void aDisabledComparisonNeverAsksForThePreviousCycle() {
        windows(0, "HDFC", d(2026, 3, 5), d(2026, 4, 4));

        KpiPeriods periods = resolver.resolve(kpi(new Comparison(false, null, null),
                date("date", CycleOperators.THIS_CYCLE), accountIs("HDFC")), ds, userId);

        assertEquals(DateRange.of(d(2026, 3, 5), d(2026, 4, 4)), periods.current().range());
        assertFalse(periods.previousAvailable());
        verify(cycles, never()).windows(any(), eq(1), any(), any());
    }

    @Test
    void noPreviousWhenTheCycleBeforeHasNoWindows() {
        windows(0, "HDFC", d(2026, 3, 5), d(2026, 4, 4));
        windows(1, "HDFC", null, null);

        KpiPeriods periods = resolver.resolve(
                kpi(null, date("date", CycleOperators.THIS_CYCLE), accountIs("HDFC")), ds, userId);

        assertTrue(periods.current().range().bounded());
        assertFalse(periods.previousAvailable());
    }

    @Test
    void withoutABillingCycleServiceCyclesAreEmpty() {
        KpiPeriodResolver plain = new KpiPeriodResolver(new DateRangeResolver(4), null);

        KpiPeriods periods = plain.resolve(
                kpi(null, date("date", CycleOperators.THIS_CYCLE), accountIs("HDFC")), ds, userId);

        assertFalse(periods.current().range().bounded());
        assertFalse(periods.previousAvailable());
        assertTrue(plain.cycleWindows(userId, 0, ds, List.of(accountIs("HDFC"))).byAccount().isEmpty());
    }

    @Test
    void cycleWindowsAreScopedToTheFilteredAccountOnTheBusinessDate() {
        windows(3, "HDFC", d(2025, 12, 5), d(2026, 1, 4));
        windows(3, null, d(2025, 12, 1), d(2025, 12, 31));

        assertEquals(d(2025, 12, 5), resolver.cycleWindows(userId, 3, ds, List.of(accountIs("HDFC"))).earliestStart());
        assertEquals(d(2025, 12, 1), resolver.cycleWindows(userId, 3, ds, List.of(typeIsDebit())).earliestStart());
        verify(cycles).windows(userId, 3, TODAY, "HDFC");
        verify(cycles).windows(userId, 3, TODAY, null);
        verify(cycles, never()).windows(any(), anyInt(), any());
    }

    /** Two date fields (the first a billing-cycle date), an account field, a type and a measure. */
    private static class PeriodDatasource implements ReportDatasource {
        @Override public String name() { return "periods"; }
        @Override public String label() { return "Periods"; }
        @Override public ReportQueryBuilder queryBuilder() { return null; }
        @Override public String billingCycleAccountField() { return "account"; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    FieldDef.cycleDate("date", "Date", List.of()),
                    new FieldDef("settlementDate", "Settlement date", FieldType.DATE, FieldRole.DIMENSION,
                            null, null, null, List.of()),
                    new FieldDef("account", "Account", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, List.of()),
                    new FieldDef("type", "Type", FieldType.ENUM, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM), null, null, List.of(), "currency"));
        }
    }
}
