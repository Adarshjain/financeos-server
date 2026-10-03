package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.BillingCycleService.AccountCycle;
import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionQueryBuilderCycleTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID cardA = UUID.randomUUID();
    private final UUID cardB = UUID.randomUUID();

    private BillingCycleService cycles;
    private TransactionQueryBuilder qb;
    private TransactionQueryBuilder qbNoCycles;

    @BeforeEach
    void setUp() {
        cycles = mock(BillingCycleService.class);
        DateRangeResolver resolver = new DateRangeResolver(4);
        qb = (TransactionQueryBuilder) new TransactionsDatasource(new SqlPredicates(resolver), resolver, cycles).queryBuilder();
        qbNoCycles = (TransactionQueryBuilder) new TransactionsDatasource(new SqlPredicates(resolver), resolver).queryBuilder();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private static FilterClause op(String field, String operator) {
        return new FilterClause(field, operator, null);
    }

    private void windows(int ago) {
        Map<UUID, Cycle> m = new LinkedHashMap<>();
        m.put(cardA, new Cycle(d(2026, 2, 5), d(2026, 3, 4), Source.PROJECTED));
        m.put(cardB, new Cycle(d(2026, 2, 20), d(2026, 3, 19), Source.STATEMENT));
        when(cycles.windows(eq(userId), eq(ago), any(LocalDate.class))).thenReturn(new CycleWindows(m));
    }

    // ---- WHERE: cycle operators ----

    @Test
    void thisBillingCycleBuildsOneBoundDisjunctPerCard() {
        windows(0);
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        String where = qb.buildWhere(List.of(op("date", "this_billing_cycle")), userId, params, joins);

        String expr = TransactionQueryBuilder.EFFECTIVE_DATE;
        assertEquals(" WHERE t.user_id = :userId AND "
                + "((t.account_id = :f0_c0a AND " + expr + " BETWEEN :f0_c0s AND :f0_c0e) OR "
                + "(t.account_id = :f0_c1a AND " + expr + " BETWEEN :f0_c1s AND :f0_c1e))", where);
        assertEquals(cardA.toString(), params.get("f0_c0a"));
        assertEquals(d(2026, 2, 5), params.get("f0_c0s"));
        assertEquals(d(2026, 3, 4), params.get("f0_c0e"));
        assertEquals(cardB.toString(), params.get("f0_c1a"));
        assertEquals(d(2026, 2, 20), params.get("f0_c1s"));
        assertEquals(d(2026, 3, 19), params.get("f0_c1e"));
    }

    @Test
    void previousBillingCycleAsksForOneCycleAgo() {
        windows(1);
        qb.buildWhere(List.of(op("date", "previous_billing_cycle")), userId, new HashMap<>(), new HashSet<>());
        verify(cycles).windows(eq(userId), eq(1), any(LocalDate.class));
    }

    @Test
    void billingCyclesAgoUsesTheAmount() {
        windows(3);
        var amount = JsonNodeFactory.instance.objectNode().put("amount", 3);
        qb.buildWhere(List.of(new FilterClause("date", "billing_cycles_ago", amount)), userId, new HashMap<>(), new HashSet<>());
        verify(cycles).windows(eq(userId), eq(3), any(LocalDate.class));
    }

    @Test
    void noCardsMatchesNothing() {
        when(cycles.windows(eq(userId), anyInt(), any(LocalDate.class))).thenReturn(new CycleWindows(Map.of()));
        String where = qb.buildWhere(List.of(op("date", "this_billing_cycle")), userId, new HashMap<>(), new HashSet<>());
        assertEquals(" WHERE t.user_id = :userId AND 1 = 0", where);
    }

    @Test
    void cyclePredicateUsesTheEffectiveDateWhicheverDateFieldCarriesTheOperator() {
        windows(0);
        Map<String, Object> params = new HashMap<>();
        String where = qb.buildWhere(List.of(op("settlementDate", "this_billing_cycle")), userId, params, new HashSet<>());
        assertTrue(where.contains(TransactionQueryBuilder.EFFECTIVE_DATE + " BETWEEN :f0_c0s"));
        assertFalse(where.contains(qb.expression("settlementDate", new HashSet<>()) + " BETWEEN"));
    }

    @Test
    void parameterNamesUseTheFilterIndex() {
        windows(0);
        Map<String, Object> params = new HashMap<>();
        String where = qb.buildWhere(List.of(new FilterClause("type", "is", com.fasterxml.jackson.databind.node.TextNode.valueOf("DEBIT")),
                op("date", "this_billing_cycle")), userId, params, new HashSet<>());
        assertTrue(where.contains(":f1_c0a"));
        assertTrue(params.containsKey("f1_c1e"));
        assertFalse(params.containsKey("f0_c0a"));
    }

    @Test
    void cycleOperatorWithoutServiceThrows() {
        assertThrows(IllegalStateException.class, () ->
                qbNoCycles.buildWhere(List.of(op("date", "this_billing_cycle")), userId, new HashMap<>(), new HashSet<>()));
    }

    @Test
    void ordinaryDateOperatorDoesNotTouchCycles() {
        String where = qb.buildWhere(List.of(op("date", "this_month")), userId, new HashMap<>(), new HashSet<>());
        assertFalse(where.contains("_c0a"));
        verify(cycles, never()).windows(any(), anyInt(), any());
    }

    // ---- billingCycle dimension ----

    @Test
    void billingCycleDimensionMapsToTheCycleTableJoin() {
        Set<String> joins = new HashSet<>();
        String expr = qb.expression("billingCycle", joins);
        assertEquals(TransactionQueryBuilder.BILLING_CYCLE_DIM, expr);
        assertTrue(joins.contains(TransactionQueryBuilder.JOIN_BILLING_CYCLES));
    }

    @Test
    void plainFromClauseRejectsTheBillingCycleJoin() {
        Set<String> joins = new HashSet<>(Set.of(TransactionQueryBuilder.JOIN_BILLING_CYCLES));
        assertThrows(IllegalStateException.class, () -> qb.fromClause(joins));
    }

    @Test
    void boundFromClauseWithoutTheJoinIsTheBaseFrom() {
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>();
        assertEquals(qb.fromClause(joins), qb.fromClause(joins, params, userId));
        assertTrue(params.isEmpty());
        verify(cycles, never()).cycleTable(any(), any());
    }

    @Test
    void boundFromClauseBuildsUnionAllCycleTable() {
        when(cycles.cycleTable(eq(userId), any(LocalDate.class))).thenReturn(List.of(
                new AccountCycle(cardA, d(2026, 1, 5), d(2026, 2, 4)),
                new AccountCycle(cardA, d(2026, 2, 5), d(2026, 3, 4))));
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>(Set.of(TransactionQueryBuilder.JOIN_BILLING_CYCLES));

        String from = qb.fromClause(joins, params, userId);

        assertTrue(from.startsWith(" FROM transactions t"));
        assertTrue(from.contains("SELECT :bc0a AS account_id, :bc0s AS cs, :bc0e AS ce FROM dual UNION ALL "
                + "SELECT :bc1a AS account_id, :bc1s AS cs, :bc1e AS ce FROM dual"));
        assertTrue(from.contains(") bc ON bc.account_id = t.account_id AND "
                + "COALESCE(t.settlement_date, t.transaction_date) BETWEEN bc.cs AND bc.ce"));
        assertEquals(cardA.toString(), params.get("bc0a"));
        assertEquals(d(2026, 1, 5), params.get("bc0s"));
        assertEquals(d(2026, 2, 4), params.get("bc0e"));
        assertEquals(d(2026, 3, 4), params.get("bc1e"));
    }

    @Test
    void boundFromClauseUsesEmptyTypedTableWhenNoRows() {
        when(cycles.cycleTable(eq(userId), any(LocalDate.class))).thenReturn(List.of());
        Map<String, Object> params = new HashMap<>();
        Set<String> joins = new HashSet<>(Set.of(TransactionQueryBuilder.JOIN_BILLING_CYCLES));

        String from = qb.fromClause(joins, params, userId);

        assertTrue(from.contains("CAST(NULL AS VARCHAR2(36)) AS account_id, CAST(NULL AS DATE) AS cs, CAST(NULL AS DATE) AS ce FROM dual WHERE 1 = 0"));
        assertTrue(params.isEmpty());
    }

    @Test
    void boundFromClauseWithoutServiceThrows() {
        Set<String> joins = new HashSet<>(Set.of(TransactionQueryBuilder.JOIN_BILLING_CYCLES));
        assertThrows(IllegalStateException.class, () -> qbNoCycles.fromClause(joins, new HashMap<>(), userId));
    }

    @Test
    void bindingFromClauseKeepsOtherJoins() {
        when(cycles.cycleTable(eq(userId), any(LocalDate.class))).thenReturn(List.of());
        Set<String> joins = new HashSet<>(Set.of(TransactionQueryBuilder.JOIN_BILLING_CYCLES, TransactionQueryBuilder.JOIN_CATEGORIES));
        String from = qb.fromClause(joins, new HashMap<>(), userId);
        assertTrue(from.contains(" categories "), from);
    }
}
