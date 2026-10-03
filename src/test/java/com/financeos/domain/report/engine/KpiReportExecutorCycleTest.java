package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KpiReportExecutorCycleTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID cardA = UUID.randomUUID();
    private final UUID cardB = UUID.randomUUID();

    private BillingCycleService cycles;
    private EntityManager em;
    private Query query;
    private KpiReportExecutor executor;
    private ReportDatasource ds;
    private final List<String> sqls = new ArrayList<>();
    private final List<Map<String, Object>> paramSets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        ds = new TransactionsDatasource(new SqlPredicates(resolver), resolver, cycles);
        executor = new KpiReportExecutor(resolver, cycles);
        em = mock(EntityManager.class);
        ReflectionTestUtils.setField(executor, "em", em);

        // One Query per createNativeQuery so each SQL's params are captured separately.
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            Map<String, Object> params = new HashMap<>();
            paramSets.add(params);
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenAnswer(pi -> {
                params.put(pi.getArgument(0), pi.getArgument(1));
                return q;
            });
            int call = sqls.size();
            when(q.getSingleResult()).thenAnswer(r -> new Object[]{call == 1 ? new BigDecimal("300") : new BigDecimal("200"), 3L});
            return q;
        });
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private void windows(int ago, UUID card, LocalDate s, LocalDate e) {
        Map<UUID, Cycle> m = new LinkedHashMap<>();
        m.put(card, new Cycle(s, e, Source.PROJECTED));
        stub(ago, new CycleWindows(m));
    }

    /** The executor reads the 4-arg (account-scoped) windows, the SQL builder the 3-arg ones. */
    private void stub(int ago, CycleWindows w) {
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class))).thenReturn(w);
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), any())).thenReturn(w);
    }

    private static KpiDefinition kpi(Comparison comparison, FilterClause... filters) {
        return new KpiDefinition("amount", Aggregation.SUM, List.of(filters), comparison);
    }

    private static FilterClause cycleFilter(String op) {
        return new FilterClause("date", op, null);
    }

    @Test
    void thisCycleKpiRunsPerCardWindowsAndComparesAgainstCycleBefore() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));

        var data = executor.execute(kpi(null, cycleFilter("this_billing_cycle")), ds, userId);

        assertEquals(2, sqls.size());
        assertEquals(new BigDecimal("300"), data.value());
        // Main query: current windows
        assertEquals(d(2026, 2, 5), paramSets.get(0).get("f0_c0s"));
        assertEquals(d(2026, 3, 4), paramSets.get(0).get("f0_c0e"));
        // Comparison query: billing_cycles_ago amount 1 -> windows(1)
        assertEquals(d(2026, 1, 5), paramSets.get(1).get("f0_c0s"));
        assertEquals(d(2026, 2, 4), paramSets.get(1).get("f0_c0e"));
        verify(cycles, atLeastOnce()).windows(eq(userId), eq(1), any(LocalDate.class), any());

        assertNotNull(data.comparison());
        assertEquals(new BigDecimal("200"), data.comparison().previousValue());
        assertEquals(new BigDecimal("100"), data.comparison().change());
        assertEquals("up", data.comparison().direction());
        assertEquals(d(2026, 1, 5), data.comparison().previousDateRange().from());
        assertEquals(d(2026, 2, 4), data.comparison().previousDateRange().to());
        assertEquals(3L, data.meta().rowCount());
        assertEquals(d(2026, 2, 5), data.meta().dateRange().from());
        assertEquals(d(2026, 3, 4), data.meta().dateRange().to());
    }

    @Test
    void previousCycleKpiComparesWithTwoCyclesAgo() {
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        windows(2, cardA, d(2025, 12, 5), d(2026, 1, 4));

        var data = executor.execute(kpi(null, cycleFilter("previous_billing_cycle")), ds, userId);

        assertEquals(d(2025, 12, 5), paramSets.get(1).get("f0_c0s"));
        assertEquals(d(2025, 12, 5), data.comparison().previousDateRange().from());
    }

    @Test
    void metaAndPreviousRangeSpanAllCards() {
        Map<UUID, Cycle> cur = new LinkedHashMap<>();
        cur.put(cardA, new Cycle(d(2026, 2, 5), d(2026, 3, 4), Source.PROJECTED));
        cur.put(cardB, new Cycle(d(2026, 2, 20), d(2026, 3, 19), Source.STATEMENT));
        Map<UUID, Cycle> prev = new LinkedHashMap<>();
        prev.put(cardA, new Cycle(d(2026, 1, 5), d(2026, 2, 4), Source.STATEMENT));
        prev.put(cardB, new Cycle(d(2026, 1, 20), d(2026, 2, 19), Source.STATEMENT));
        stub(0, new CycleWindows(cur));
        stub(1, new CycleWindows(prev));

        var data = executor.execute(kpi(null, cycleFilter("this_billing_cycle")), ds, userId);

        assertEquals(d(2026, 2, 5), data.meta().dateRange().from());
        assertEquals(d(2026, 3, 19), data.meta().dateRange().to());
        assertEquals(d(2026, 1, 5), data.comparison().previousDateRange().from());
        assertEquals(d(2026, 2, 19), data.comparison().previousDateRange().to());
    }

    @Test
    void comparisonDisabledRunsOnlyTheMainQuery() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        // Forced by: the single KPI path always resolves the previous cycle's span (no longer skipped when disabled).
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));

        var data = executor.execute(kpi(new Comparison(false, null, null), cycleFilter("this_billing_cycle")), ds, userId);

        assertEquals(1, sqls.size());
        assertNull(data.comparison());
        assertNotNull(data.meta().dateRange());
    }

    @Test
    void explicitlyEnabledComparisonCarriesHigherIsBetterSentiment() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));

        var data = executor.execute(kpi(new Comparison(true, null, false), cycleFilter("this_billing_cycle")), ds, userId);

        assertEquals("bad", data.comparison().sentiment());
    }

    @Test
    void noCardsMeansNoComparisonAndNoDateRange() {
        stub(0, new CycleWindows(Map.of()));
        stub(1, new CycleWindows(Map.of()));

        var data = executor.execute(kpi(null, cycleFilter("this_billing_cycle")), ds, userId);

        assertEquals(1, sqls.size());
        assertTrue(sqls.get(0).contains("1 = 0"));
        assertNull(data.comparison());
        assertNull(data.meta().dateRange());
    }

    @Test
    void otherFiltersAreKeptAndOnlyTheCycleFilterIsReplacedInComparison() {
        windows(0, cardA, d(2026, 2, 5), d(2026, 3, 4));
        windows(1, cardA, d(2026, 1, 5), d(2026, 2, 4));
        FilterClause type = new FilterClause("type", "is", com.fasterxml.jackson.databind.node.TextNode.valueOf("DEBIT"));

        executor.execute(kpi(null, type, cycleFilter("this_billing_cycle")), ds, userId);

        assertEquals(2, sqls.size());
        for (Map<String, Object> p : paramSets) {
            assertTrue(p.containsValue("DEBIT"), "type filter kept in both queries");
        }
    }

    // ---- withCyclesAgo ----

    @Test
    void withCyclesAgoReplacesOnlyTheDateFilter() {
        FilterClause type = new FilterClause("type", "is", com.fasterxml.jackson.databind.node.TextNode.valueOf("DEBIT"));
        FilterClause date = cycleFilter("this_billing_cycle");

        List<FilterClause> out = KpiReportExecutor.withCyclesAgo(List.of(date, type), date, 2);

        assertEquals(2, out.size());
        assertSame(type, out.get(0));
        FilterClause replaced = out.get(1);
        assertEquals("date", replaced.field());
        assertEquals(CycleOperators.CYCLES_AGO, replaced.operator());
        assertEquals(2, replaced.value().get("amount").asInt());
    }
}
