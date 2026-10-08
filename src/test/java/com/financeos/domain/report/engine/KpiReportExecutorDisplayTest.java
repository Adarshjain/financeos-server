package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonDisplay;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The comparison's {@code display} echo and the pass-through of a null previous value (SQL KPI path). */
class KpiReportExecutorDisplayTest {

    private final UUID userId = UUID.randomUUID();
    private KpiReportExecutor executor;
    private ReportDatasource ds;
    /** Aggregate values returned per native query, in call order (main query first, then the prior period). */
    private final List<BigDecimal> results = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BillingCycleService cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        ds = new TransactionsDatasource(new SqlPredicates(resolver), resolver, cycles);
        executor = new KpiReportExecutor(resolver, cycles);
        EntityManager em = mock(EntityManager.class);
        ReflectionTestUtils.setField(executor, "em", em);
        List<String> sqls = new ArrayList<>();
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            sqls.add(inv.getArgument(0));
            int call = sqls.size();
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenReturn(q);
            when(q.getSingleResult()).thenAnswer(r -> {
                BigDecimal value = results.get(call - 1);
                return new Object[]{value, value == null ? 0L : 2L};
            });
            return q;
        });
    }

    private static KpiDefinition kpi(Aggregation aggregation, Comparison comparison) {
        ObjectNode may = JsonNodeFactory.instance.objectNode();
        may.put("from", "2026-05-01");
        may.put("to", "2026-05-31");
        return new KpiDefinition("amount", aggregation, List.of(new FilterClause("date", "between", may)), comparison);
    }

    @Test
    void echoesPreviousValueDisplayWhenRequested() {
        results.add(new BigDecimal("300"));
        results.add(new BigDecimal("200"));

        var data = executor.execute(kpi(Aggregation.SUM,
                new Comparison(true, null, true, ComparisonDisplay.PREVIOUS_VALUE)), ds, userId);

        assertEquals("previous_value", data.comparison().display());
        assertEquals(new BigDecimal("200"), data.comparison().previousValue());
        assertEquals(new BigDecimal("100"), data.comparison().change());
    }

    @Test
    void displayDefaultsToChangeWhenTheBlockOrItsDisplayIsAbsent() {
        // Two executions, two queries each; the query counter runs across both.
        results.addAll(List.of(new BigDecimal("300"), new BigDecimal("200"), new BigDecimal("300"), new BigDecimal("200")));

        assertEquals("change", executor.execute(kpi(Aggregation.SUM, null), ds, userId).comparison().display());
        assertEquals("change", executor.execute(kpi(Aggregation.SUM, new Comparison(true, null, null)), ds, userId)
                .comparison().display());
    }

    @Test
    void previousValueStaysNullWhenThePriorPeriodHadNoRowsForAvg() {
        results.add(new BigDecimal("150"));
        results.add(null);

        var data = executor.execute(kpi(Aggregation.AVG,
                new Comparison(true, null, null, ComparisonDisplay.PREVIOUS_VALUE)), ds, userId);

        assertNull(data.comparison().previousValue());
        // The change itself still treats the missing prior value as zero.
        assertEquals(new BigDecimal("150"), data.comparison().change());
        assertNull(data.comparison().changePercent());
        assertEquals("up", data.comparison().direction());
    }

    @Test
    void sumOverAnEmptyPriorPeriodEchoesZeroNotNull() {
        results.add(new BigDecimal("150"));
        results.add(null);

        var data = executor.execute(kpi(Aggregation.SUM,
                new Comparison(true, null, null, ComparisonDisplay.PREVIOUS_VALUE)), ds, userId);

        assertEquals(BigDecimal.ZERO, data.comparison().previousValue());
    }
}
