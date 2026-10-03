package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** KPI date range and comparison come from the one account the report is limited to. */
class KpiReportExecutorSingleAccountTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 11);

    private final UUID userId = UUID.randomUUID();
    private final UUID card = UUID.randomUUID();

    private BillingCycleService cycles;
    private KpiReportExecutor executor;
    private TransactionsDatasource ds;
    private final List<String> sqls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 20:00 UTC on 10 March is 11 March in India: "today" must be the IST date
        AppTime.useClock(Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Kolkata")));
        cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        ds = new TransactionsDatasource(new SqlPredicates(resolver), resolver, cycles);
        executor = new KpiReportExecutor(resolver, cycles);
        EntityManager em = mock(EntityManager.class);
        ReflectionTestUtils.setField(executor, "em", em);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenReturn(q);
            when(q.getSingleResult()).thenReturn(new Object[]{new BigDecimal("10"), 1L});
            return q;
        });
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private void stub(int ago, String ref, UUID account, LocalDate start, LocalDate end) {
        Map<UUID, Cycle> m = new LinkedHashMap<>();
        if (account != null) {
            m.put(account, new Cycle(start, end, Source.PROJECTED));
        }
        CycleWindows w = new CycleWindows(m);
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), eq(ref))).thenReturn(w);
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class))).thenReturn(w);
    }

    private static FilterClause cycle(String op) {
        return new FilterClause("date", op, null);
    }

    private static KpiDefinition kpi(Comparison c, FilterClause... f) {
        return new KpiDefinition("amount", Aggregation.SUM, List.of(f), c);
    }

    private static FilterClause accountIs(String ref) {
        return new FilterClause("account", "is", TextNode.valueOf(ref));
    }

    @Test
    void metaAndComparisonRangesAreTheSingleAccountsCycles() {
        stub(0, "HDFC", card, d(2026, 3, 5), d(2026, 4, 4));
        stub(1, "HDFC", card, d(2026, 2, 5), d(2026, 3, 4));

        var data = executor.execute(kpi(null, cycle("this_billing_cycle"), accountIs("HDFC")), ds, userId);

        assertEquals(d(2026, 3, 5), data.meta().dateRange().from());
        assertEquals(d(2026, 4, 4), data.meta().dateRange().to());
        assertEquals(d(2026, 2, 5), data.comparison().previousDateRange().from());
        assertEquals(d(2026, 3, 4), data.comparison().previousDateRange().to());
        assertEquals(2, sqls.size());
    }

    @Test
    void windowsAreRequestedForTheAccountRefAndTheBusinessDate() {
        stub(0, "HDFC", card, d(2026, 3, 5), d(2026, 4, 4));
        stub(1, "HDFC", card, d(2026, 2, 5), d(2026, 3, 4));

        executor.execute(kpi(null, cycle("this_billing_cycle"), accountIs("HDFC")), ds, userId);

        verify(cycles).windows(userId, 0, TODAY, "HDFC");
        verify(cycles).windows(userId, 1, TODAY, "HDFC");
    }

    @Test
    void anInFilterOfOneValueScopesTheWindowsToo() {
        var one = JsonNodeFactory.instance.arrayNode().add("HDFC");
        stub(0, "HDFC", card, d(2026, 3, 5), d(2026, 4, 4));
        stub(1, "HDFC", card, d(2026, 2, 5), d(2026, 3, 4));

        executor.execute(kpi(null, cycle("this_billing_cycle"), new FilterClause("account", "in", one)), ds, userId);

        verify(cycles).windows(userId, 0, TODAY, "HDFC");
    }

    @Test
    void withoutAnAccountFilterTheWindowsAreNotScoped() {
        stub(0, null, card, d(2026, 3, 5), d(2026, 4, 4));
        stub(1, null, card, d(2026, 2, 5), d(2026, 3, 4));

        executor.execute(kpi(null, cycle("this_billing_cycle")), ds, userId);

        verify(cycles).windows(userId, 0, TODAY, null);
    }

    @Test
    void previousBillingCycleShiftsTheComparisonOneCycleFurtherBack() {
        stub(1, "HDFC", card, d(2026, 2, 5), d(2026, 3, 4));
        stub(2, "HDFC", card, d(2026, 1, 5), d(2026, 2, 4));

        var data = executor.execute(kpi(null, cycle("previous_billing_cycle"), accountIs("HDFC")), ds, userId);

        assertEquals(d(2026, 2, 5), data.meta().dateRange().from());
        assertEquals(d(2026, 1, 5), data.comparison().previousDateRange().from());
        verify(cycles).windows(userId, 2, TODAY, "HDFC");
    }

    @Test
    void noPreviousCycleMeansNoComparisonButTheCurrentRangeStays() {
        stub(0, "HDFC", card, d(2026, 3, 5), d(2026, 4, 4));
        stub(1, "HDFC", null, null, null);

        var data = executor.execute(kpi(null, cycle("this_billing_cycle"), accountIs("HDFC")), ds, userId);

        assertNull(data.comparison());
        assertEquals(1, sqls.size());
        assertEquals(d(2026, 3, 5), data.meta().dateRange().from());
    }

    @Test
    void anAccountWithoutWindowsGivesNoRangeAndNoComparison() {
        stub(0, "nobody", null, null, null);
        stub(1, "nobody", null, null, null);

        var data = executor.execute(kpi(null, cycle("this_billing_cycle"), accountIs("nobody")), ds, userId);

        assertNull(data.comparison());
        assertNull(data.meta().dateRange());
        assertEquals(1, sqls.size());
    }

    @Test
    void comparisonCanBeDisabled() {
        stub(0, "HDFC", card, d(2026, 3, 5), d(2026, 4, 4));
        stub(1, "HDFC", card, d(2026, 2, 5), d(2026, 3, 4));

        var data = executor.execute(kpi(new Comparison(false, null, null), cycle("this_billing_cycle"), accountIs("HDFC")), ds, userId);

        assertNull(data.comparison());
        assertEquals(1, sqls.size());
    }

    @Test
    void aNonCycleDateFilterNeverTouchesTheCycleService() {
        var data = executor.execute(kpi(null, new FilterClause("date", "this_month", null)), ds, userId);

        verify(cycles, never()).windows(any(), anyInt(), any(), any());
        verify(cycles, never()).windows(any(), anyInt(), any());
        assertNotNull(data.comparison());
        assertEquals(2, sqls.size());
    }

    // ---- span ----

    @Test
    void spanOfNoWindowsIsUnbounded() {
        assertFalse(KpiReportExecutor.span(new CycleWindows(Map.of())).bounded());
    }

    @Test
    void spanCoversTheEarliestStartThroughTheLatestEnd() {
        Map<UUID, Cycle> m = new LinkedHashMap<>();
        m.put(card, new Cycle(d(2026, 3, 5), d(2026, 4, 4), Source.PROJECTED));
        m.put(UUID.randomUUID(), new Cycle(d(2026, 3, 1), d(2026, 3, 31), Source.CALENDAR_MONTH));

        DateRange range = KpiReportExecutor.span(new CycleWindows(m));

        assertTrue(range.bounded());
        assertEquals(d(2026, 3, 1), range.from());
        assertEquals(d(2026, 4, 4), range.to());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
