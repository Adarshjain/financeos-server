package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** In-memory cycle filters: scoped to the single account of the report; rows matched via the account field's idField. */
class InMemoryReportExecutorAccountScopeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 11);

    private final UUID userId = UUID.randomUUID();
    private final UUID cardA = UUID.randomUUID();
    private final UUID cardB = UUID.randomUUID();

    private BillingCycleService cycles;
    private InMemoryReportExecutor executor;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(Instant.parse("2026-03-10T20:00:00Z"), ZoneId.of("Asia/Kolkata")));
        cycles = mock(BillingCycleService.class);
        executor = new InMemoryReportExecutor(new DateRangeResolver(4), cycles);
        // Forced by the KPI comparison now being on by default: it reads the previous cycle's windows,
        // which the real service returns empty (never null) when a test does not stub them.
        when(cycles.windows(any(), anyInt(), any(), any())).thenReturn(new CycleWindows(Map.of()));
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private void windows(int ago, String ref, UUID account, LocalDate s, LocalDate e) {
        Map<UUID, Cycle> m = new LinkedHashMap<>();
        if (account != null) {
            m.put(account, new Cycle(s, e, Source.PROJECTED));
        }
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class), eq(ref))).thenReturn(new CycleWindows(m));
    }

    private static Map<String, Object> row(String idKey, Object id, LocalDate date, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put(idKey, id);
        m.put("card", "label");
        m.put("date", date);
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    private static KpiDefinition kpi(Comparison c, FilterClause... f) {
        return new KpiDefinition("amount", Aggregation.SUM, List.of(f), c);
    }

    private static FilterClause cycle() {
        return new FilterClause("date", "this_billing_cycle", null);
    }

    private static FilterClause cardIs(UUID id) {
        return new FilterClause("card", "is", TextNode.valueOf(id.toString()));
    }

    // ---- accountRef scoping ----

    @Test
    void windowsAreScopedToTheCardFilterAndUseTheBusinessDate() {
        windows(0, cardA.toString(), cardA, d(2026, 3, 5), d(2026, 4, 4));
        Ds ds = new Ds("card", "cardId");
        ds.rows = List.of(row("cardId", cardA, d(2026, 3, 10), "5"), row("cardId", cardB, d(2026, 3, 10), "50"));

        var data = executor.execute(kpi(null, cycle(), cardIs(cardA)), ds, Map.of());

        assertEquals(new BigDecimal("5"), data.value());
        verify(cycles, atLeastOnce()).windows(userId, 0, TODAY, cardA.toString());
    }

    @Test
    void anInFilterOfOneValueScopesTheWindowsToo() {
        windows(0, cardA.toString(), cardA, d(2026, 3, 5), d(2026, 4, 4));
        Ds ds = new Ds("card", "cardId");
        ds.rows = List.of(row("cardId", cardA, d(2026, 3, 10), "5"));
        var one = JsonNodeFactory.instance.arrayNode().add(cardA.toString());

        executor.execute(kpi(null, cycle(), new FilterClause("card", "in", one)), ds, Map.of());

        verify(cycles, atLeastOnce()).windows(userId, 0, TODAY, cardA.toString());
    }

    @Test
    void withoutAnAccountFilterTheWindowsAreNotScoped() {
        windows(0, null, cardA, d(2026, 3, 5), d(2026, 4, 4));
        Ds ds = new Ds("card", "cardId");
        ds.rows = List.of(row("cardId", cardA, d(2026, 3, 10), "5"));

        assertEquals(new BigDecimal("5"), executor.execute(kpi(null, cycle()), ds, Map.of()).value());
        verify(cycles, atLeastOnce()).windows(userId, 0, TODAY, null);
    }

    @Test
    void comparisonUsesTheScopedPreviousCycle() {
        windows(0, cardA.toString(), cardA, d(2026, 3, 5), d(2026, 4, 4));
        windows(1, cardA.toString(), cardA, d(2026, 2, 5), d(2026, 3, 4));
        Ds ds = new Ds("card", "cardId");
        ds.rows = List.of(row("cardId", cardA, d(2026, 3, 10), "30"), row("cardId", cardA, d(2026, 2, 10), "10"));

        var data = executor.execute(kpi(new Comparison(true, null, true), cycle(), cardIs(cardA)), ds, Map.of());

        assertEquals(new BigDecimal("30"), data.value());
        assertEquals(new BigDecimal("10"), data.comparison().previousValue());
        assertEquals(new BigDecimal("20"), data.comparison().change());
        assertEquals("up", data.comparison().direction());
        assertEquals("good", data.comparison().sentiment());
        assertEquals(d(2026, 2, 5), data.comparison().previousDateRange().from());
        assertEquals(d(2026, 3, 4), data.comparison().previousDateRange().to());
        assertEquals(d(2026, 3, 5), data.meta().dateRange().from());
        verify(cycles, atLeastOnce()).windows(userId, 1, TODAY, cardA.toString());
    }

    // ---- accountOf via the account field's idField ----

    @Test
    void rowsAreMatchedThroughTheAccountFieldsIdField() {
        windows(0, null, cardA, d(2026, 3, 5), d(2026, 4, 4));
        Ds ds = new Ds("card", "acctId");
        ds.rows = List.of(
                row("acctId", cardA, d(2026, 3, 10), "1"),   // id under the idField: matches
                row("cardId", cardA, d(2026, 3, 10), "10")); // id under another key: no id -> no match

        assertEquals(new BigDecimal("1"), executor.execute(kpi(null, cycle()), ds, Map.of()).value());
    }

    @Test
    void anAccountFieldWithoutAnIdFieldMatchesNothing() {
        windows(0, null, cardA, d(2026, 3, 5), d(2026, 4, 4));
        Ds ds = new Ds("card", null);
        ds.rows = List.of(row("cardId", cardA, d(2026, 3, 10), "10"));

        assertEquals(BigDecimal.ZERO, executor.execute(kpi(null, cycle()), ds, Map.of()).value());
    }

    @Test
    void aBillingCycleAccountFieldMissingFromTheFieldsMatchesNothing() {
        windows(0, null, cardA, d(2026, 3, 5), d(2026, 4, 4));
        Ds ds = new Ds("ghost", "cardId");
        ds.rows = List.of(row("cardId", cardA, d(2026, 3, 10), "10"));

        assertEquals(BigDecimal.ZERO, executor.execute(kpi(null, cycle()), ds, Map.of()).value());
    }

    @Test
    void aDatasourceWithoutABillingCycleAccountFieldMatchesNothing() {
        windows(0, null, cardA, d(2026, 3, 5), d(2026, 4, 4));
        Ds ds = new Ds(null, "cardId");
        ds.rows = List.of(row("cardId", cardA, d(2026, 3, 10), "10"));

        assertEquals(BigDecimal.ZERO, executor.execute(kpi(null, cycle()), ds, Map.of()).value());
    }

    // ---- datasource ----

    private static class Ds implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();
        private final String accountField;
        private final String idField;

        Ds(String accountField, String idField) {
            this.accountField = accountField;
            this.idField = idField;
        }

        @Override public String name() { return "acc"; }
        @Override public String label() { return "Acc"; }
        @Override public String billingCycleAccountField() { return accountField; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    FieldDef.cycleDate("date", "Date", List.of()),
                    new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, List.of(), null, idField),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM, Aggregation.COUNT), null, null, List.of(), "currency"));
        }

        @Override public List<Map<String, Object>> rows() { return rows; }
        @Override public List<Map<String, Object>> rows(DateHint hint) { return rows; }
    }
}
