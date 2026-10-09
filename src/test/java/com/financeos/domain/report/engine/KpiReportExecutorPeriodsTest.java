package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The SQL KPI reads both of its periods from {@link KpiPeriodResolver}. */
class KpiReportExecutorPeriodsTest {

    private final UUID userId = UUID.randomUUID();
    private DateRangeResolver dateRangeResolver;
    private BillingCycleService cycles;
    private KpiReportExecutor executor;
    private ReportDatasource ds;
    private final List<Map<String, Object>> paramSets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Kolkata")));
        cycles = mock(BillingCycleService.class);
        dateRangeResolver = new DateRangeResolver(4);
        ds = new TransactionsDatasource(new SqlPredicates(dateRangeResolver), dateRangeResolver, cycles);
        executor = new KpiReportExecutor(dateRangeResolver, cycles);
        EntityManager em = mock(EntityManager.class);
        ReflectionTestUtils.setField(executor, "em", em);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            Map<String, Object> params = new HashMap<>();
            paramSets.add(params);
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenAnswer(pi -> {
                params.put(pi.getArgument(0), pi.getArgument(1));
                return q;
            });
            when(q.getSingleResult()).thenReturn(new Object[]{new BigDecimal("10"), 1L});
            return q;
        });
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    @Test
    void theComparisonQueryRunsTheResolversPreviousPeriod() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, List.of(
                new FilterClause("date", "this_month", null), new FilterClause("type", "is", TextNode.valueOf("DEBIT"))),
                null);
        KpiPeriods periods = new KpiPeriodResolver(dateRangeResolver, cycles).resolve(def, ds, userId);

        KpiData data = executor.execute(def, ds, userId);

        assertEquals(2, paramSets.size());
        DateRange previous = periods.previous().range();
        assertTrue(paramSets.get(1).containsValue(previous.from()), "previous period start bound");
        assertTrue(paramSets.get(1).containsValue(previous.to()), "previous period end bound");
        assertTrue(paramSets.get(1).containsValue("DEBIT"), "other filters kept");
        assertFalse(paramSets.get(0).containsValue(previous.from()));
        assertEquals(previous.from(), data.comparison().previousDateRange().from());
        assertEquals(previous.to(), data.comparison().previousDateRange().to());
        assertEquals(periods.current().range().from(), data.meta().dateRange().from());
        assertEquals(LocalDate.of(2026, 2, 1), previous.from());
    }
}
