package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonDisplay;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The in-memory KPI builds its previous-period comparison exactly as the SQL KPI does, for every
 * aggregation, both displays and every sentiment setting — including an empty previous period,
 * where AVG/MIN/MAX have no previous value: the comparison is still there, with a null
 * {@code previousValue}, the change against zero and no percentage.
 */
class KpiComparisonParityTest {

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final LocalDate PREVIOUS_FROM = LocalDate.of(2026, 3, 31);
    private static final LocalDate PREVIOUS_TO = LocalDate.of(2026, 4, 30);

    private final UUID userId = UUID.randomUUID();
    private KpiReportExecutor sql;
    private InMemoryReportExecutor inMemory;
    private TransactionsDatasource transactions;
    /** The SQL aggregates per native query, in call order (current period, then previous). */
    private final List<BigDecimal> sqlResults = new ArrayList<>();
    private List<Map<String, Object>> rows = List.of();

    private final ComputedReportDatasource computed = new ComputedReportDatasource() {
        @Override
        public String name() {
            return "computed";
        }

        @Override
        public String label() {
            return "Computed";
        }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, ALL),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, Arrays.asList(Aggregation.values()),
                            null, null, ALL, "currency"));
        }

        @Override
        public List<Map<String, Object>> rows() {
            return rows;
        }
    };

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        BillingCycleService cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        transactions = new TransactionsDatasource(new SqlPredicates(resolver), resolver, cycles);
        sql = new KpiReportExecutor(resolver, cycles);
        inMemory = new InMemoryReportExecutor(resolver, cycles);
        EntityManager em = mock(EntityManager.class);
        ReflectionTestUtils.setField(sql, "em", em);
        List<String> calls = new ArrayList<>();
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            calls.add(inv.getArgument(0));
            int call = calls.size();
            Query q = mock(Query.class);
            when(q.setParameter(anyString(), any())).thenReturn(q);
            when(q.getSingleResult()).thenAnswer(r -> {
                BigDecimal value = sqlResults.get(call - 1);
                return new Object[]{value, value == null ? 0L : 1L};
            });
            return q;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    static Stream<Arguments> settings() {
        List<Arguments> out = new ArrayList<>();
        for (Aggregation aggregation : Aggregation.values()) {
            for (ComparisonDisplay display : ComparisonDisplay.values()) {
                for (Boolean higherIsBetter : Arrays.asList(true, false, null)) {
                    out.add(Arguments.of(aggregation, display, higherIsBetter));
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("settings")
    void anEmptyPreviousPeriodComparesAgainstNothingLikeTheSqlKpi(Aggregation aggregation, ComparisonDisplay display,
                                                                  Boolean higherIsBetter) {
        rows = List.of(row(LocalDate.of(2026, 5, 3), "10"), row(LocalDate.of(2026, 5, 20), "30"));
        KpiDefinition def = kpi(aggregation, new Comparison(true, null, higherIsBetter, display));

        KpiData memory = inMemory.execute(def, computed, Map.of());
        // The database answers NULL for an empty period, whatever the aggregation.
        KpiData database = runSql(def, memory.value(), null);

        KpiData.Comparison comparison = memory.comparison();
        assertNotNull(comparison);
        assertEquals(database.comparison(), comparison);
        boolean zeroWhenEmpty = aggregation == Aggregation.SUM || aggregation == Aggregation.COUNT;
        assertEquals(zeroWhenEmpty ? BigDecimal.ZERO : null, comparison.previousValue());
        assertEquals(new KpiData.DateRangeView(PREVIOUS_FROM, PREVIOUS_TO), comparison.previousDateRange());
        assertEquals(0, memory.value().compareTo(comparison.change()), "the change is against zero");
        assertNull(comparison.changePercent());
        assertEquals("up", comparison.direction());
        assertEquals(higherIsBetter == null ? "neutral" : higherIsBetter ? "good" : "bad", comparison.sentiment());
        assertEquals(display.json(), comparison.display());
    }

    @ParameterizedTest
    @MethodSource("settings")
    void aPreviousPeriodWithRowsComparesLikeTheSqlKpi(Aggregation aggregation, ComparisonDisplay display,
                                                      Boolean higherIsBetter) {
        rows = List.of(row(LocalDate.of(2026, 5, 3), "10"), row(LocalDate.of(2026, 4, 10), "30"),
                row(LocalDate.of(2026, 4, 12), "60"));
        KpiDefinition def = kpi(aggregation, new Comparison(true, null, higherIsBetter, display));

        KpiData memory = inMemory.execute(def, computed, Map.of());
        KpiData database = runSql(def, memory.value(), memory.comparison().previousValue());

        assertEquals(database.comparison(), memory.comparison());
        assertNotNull(memory.comparison().changePercent());
        assertEquals(2, memory.comparison().changePercent().scale(), "percent to two decimals, as on the SQL path");
    }

    @ParameterizedTest
    @MethodSource("settings")
    void anEmptyCurrentPeriodComparesAsZeroLikeTheSqlKpi(Aggregation aggregation, ComparisonDisplay display,
                                                         Boolean higherIsBetter) {
        rows = List.of(row(LocalDate.of(2026, 4, 10), "30"));
        KpiDefinition def = kpi(aggregation, new Comparison(true, null, higherIsBetter, display));

        KpiData memory = inMemory.execute(def, computed, Map.of());
        KpiData database = runSql(def, sqlCurrent(aggregation, memory.value()), memory.comparison().previousValue());

        assertEquals(database.comparison(), memory.comparison());
        assertEquals("down", memory.comparison().direction());
    }

    // ------------------------------------------------------------------ helpers

    /** The SQL KPI over the same figures: {@code current} then {@code previous} from the database. */
    private KpiData runSql(KpiDefinition def, BigDecimal current, BigDecimal previous) {
        sqlResults.clear();
        sqlResults.add(current);
        sqlResults.add(previous);
        return sql.execute(def, transactions, userId);
    }

    /** The database's NULL for an empty current period (the executor makes SUM/COUNT zero). */
    private static BigDecimal sqlCurrent(Aggregation aggregation, BigDecimal inMemoryValue) {
        return aggregation == Aggregation.SUM || aggregation == Aggregation.COUNT ? null : inMemoryValue;
    }

    private static KpiDefinition kpi(Aggregation aggregation, Comparison comparison) {
        ObjectNode may = JsonNodeFactory.instance.objectNode();
        may.put("from", "2026-05-01");
        may.put("to", "2026-05-31");
        return new KpiDefinition("amount", aggregation, List.of(new FilterClause("date", "between", may)), comparison);
    }

    private static Map<String, Object> row(LocalDate date, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", date);
        m.put("amount", new BigDecimal(amount));
        return m;
    }
}
