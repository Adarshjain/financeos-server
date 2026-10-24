package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonPeriod;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.MeasureRef;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** A chart comparison on a billing-cycle filter reads the cycle before, like the KPI does. */
class ChartReportExecutorCycleTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID card = UUID.randomUUID();
    private BillingCycleService cycles;
    private ChartReportExecutor executor;
    private ReportDatasource ds;
    private final List<Map<String, Object>> paramSets = new ArrayList<>();
    private int grouped;

    @BeforeEach
    void setUp() {
        cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        ds = new TransactionsDatasource(new SqlPredicates(resolver), resolver, cycles);
        executor = new ChartReportExecutor(resolver, cycles);
        EntityManager em = mock(EntityManager.class);
        ReflectionTestUtils.setField(executor, "em", em);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            Map<String, Object> params = new HashMap<>();
            paramSets.add(params);
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenAnswer(pi -> {
                params.put(pi.getArgument(0), pi.getArgument(1));
                return q;
            });
            when(q.getSingleResult()).thenReturn(3L);
            if (sql.contains(" AS cnt")) {
                BigDecimal v = grouped++ == 0 ? new BigDecimal("300") : new BigDecimal("200");
                when(q.getResultList()).thenReturn(Arrays.<Object[]>asList(new Object[]{"Dining", v, 3L, 3L, v}));
            }
            return q;
        });
        window(0, LocalDate.of(2026, 2, 5), LocalDate.of(2026, 3, 4));
        window(1, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 4));
    }

    private void window(int ago, LocalDate s, LocalDate e) {
        Map<UUID, Cycle> m = new LinkedHashMap<>();
        m.put(card, new Cycle(s, e, Source.PROJECTED));
        CycleWindows w = new CycleWindows(m);
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class))).thenReturn(w);
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), any())).thenReturn(w);
    }

    private ChartDefinition chart(Comparison comparison) {
        return new ChartDefinition(ChartType.BAR, new DimensionRef("category", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of(new FilterClause("date", "this_billing_cycle", null)),
                null, null, null, comparison, null, null);
    }

    @Test
    void thisCycleChartComparesWithTheCycleBefore() {
        ChartData data = executor.execute(chart(new Comparison(true, ComparisonPeriod.PREVIOUS_PERIOD, null)), ds, userId);

        assertEquals(List.of(new BigDecimal("300")), data.series().get(0).data());
        assertEquals(List.of(new BigDecimal("200")), data.previous().series().get(0).data());
        assertEquals(LocalDate.of(2026, 2, 5), data.meta().dateRange().from());
        assertEquals(LocalDate.of(2026, 3, 4), data.meta().dateRange().to());
        assertEquals(LocalDate.of(2026, 1, 5), data.previous().dateRange().from());
        assertEquals(LocalDate.of(2026, 2, 4), data.previous().dateRange().to());
        assertTrue(paramSets.stream().anyMatch(p -> LocalDate.of(2026, 1, 5).equals(p.get("f0_c0s"))), "previous cycle queried");
        verify(cycles, atLeastOnce()).windows(eq(userId), eq(1), any(LocalDate.class), any());
    }

    @Test
    void withoutAComparisonTheCycleChartHasNoPreviousAndKeepsItsOldRange() {
        ChartData data = executor.execute(chart(null), ds, userId);
        assertNull(data.previous());
        assertNull(data.meta().dateRange()); // cycle ranges are per account; unchanged behaviour
    }
}
