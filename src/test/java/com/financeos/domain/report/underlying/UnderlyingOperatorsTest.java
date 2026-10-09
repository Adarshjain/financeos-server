package com.financeos.domain.report.underlying;

import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal clauses that narrow a KPI period to its listed rows. */
class UnderlyingOperatorsTest {

    @ParameterizedTest
    @EnumSource(value = Aggregation.class, names = {"SUM", "AVG", "COUNT"})
    void additiveAggregationsListEveryRowWithTheMeasure(Aggregation aggregation) {
        List<FilterClause> clauses = UnderlyingOperators.listing("amount", aggregation, new BigDecimal("12.5"));

        assertEquals(List.of(new FilterClause("amount", UnderlyingOperators.PRESENT, null)), clauses);
        assertFalse(UnderlyingOperators.winnerOnly(aggregation));
    }

    @ParameterizedTest
    @EnumSource(value = Aggregation.class, names = {"MIN", "MAX"})
    void minAndMaxListOnlyTheRowsEqualToTheValue(Aggregation aggregation) {
        List<FilterClause> clauses = UnderlyingOperators.listing("amount", aggregation, new BigDecimal("-40.50"));

        assertEquals(2, clauses.size());
        assertEquals(new FilterClause("amount", UnderlyingOperators.PRESENT, null), clauses.get(0));
        assertEquals("amount", clauses.get(1).field());
        assertEquals(UnderlyingOperators.EQUAL_TO, clauses.get(1).operator());
        assertEquals(0, clauses.get(1).value().decimalValue().compareTo(new BigDecimal("-40.50")));
        assertTrue(UnderlyingOperators.winnerOnly(aggregation));
    }

    @ParameterizedTest
    @EnumSource(value = Aggregation.class, names = {"MIN", "MAX"})
    void minAndMaxWithoutAValueKeepOnlyThePresenceClause(Aggregation aggregation) {
        List<FilterClause> clauses = UnderlyingOperators.listing("amount", aggregation, null);

        assertEquals(1, clauses.size());
        assertEquals(UnderlyingOperators.PRESENT, clauses.get(0).operator());
        assertNull(clauses.get(0).value());
    }

    @Test
    void onlyTheTwoInternalOperatorsAreInternal() {
        assertTrue(UnderlyingOperators.isInternal(UnderlyingOperators.PRESENT));
        assertTrue(UnderlyingOperators.isInternal(UnderlyingOperators.EQUAL_TO));
        assertFalse(UnderlyingOperators.isInternal("equals"));
        assertFalse(UnderlyingOperators.isInternal("billing_cycles_ago"));
        assertFalse(UnderlyingOperators.isInternal(null));
    }
}
