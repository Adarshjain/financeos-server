package com.financeos.domain.account.cycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CycleOperatorsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void publicOperatorsExcludeTheInternalOne() {
        assertEquals(List.of("this_billing_cycle", "previous_billing_cycle"), CycleOperators.PUBLIC);
        assertFalse(CycleOperators.PUBLIC.contains(CycleOperators.CYCLES_AGO));
    }

    @Test
    void isCycleRecognisesAllThreeOperators() {
        assertTrue(CycleOperators.isCycle("this_billing_cycle"));
        assertTrue(CycleOperators.isCycle("previous_billing_cycle"));
        assertTrue(CycleOperators.isCycle("billing_cycles_ago"));
    }

    @Test
    void isCycleRejectsOtherAndNullOperators() {
        assertFalse(CycleOperators.isCycle("this_month"));
        assertFalse(CycleOperators.isCycle((String) null));
    }

    @Test
    void isCycleOnFilterClause() {
        assertTrue(CycleOperators.isCycle(new FilterClause("date", "this_billing_cycle", null)));
        assertFalse(CycleOperators.isCycle(new FilterClause("date", "this_month", null)));
        assertFalse(CycleOperators.isCycle((FilterClause) null));
    }

    @Test
    void thisAndPreviousAreZeroAndOne() {
        assertEquals(0, CycleOperators.cyclesAgo("this_billing_cycle", null));
        assertEquals(1, CycleOperators.cyclesAgo("previous_billing_cycle", null));
    }

    @Test
    void cyclesAgoUsesAmount() throws Exception {
        assertEquals(3, CycleOperators.cyclesAgo("billing_cycles_ago", json("{\"amount\":3}")));
        assertEquals(0, CycleOperators.cyclesAgo("billing_cycles_ago", json("{\"amount\":0}")));
    }

    @Test
    void cyclesAgoRejectsMissingValueOrAmount() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> CycleOperators.cyclesAgo("billing_cycles_ago", null));
        assertThrows(IllegalArgumentException.class, () -> CycleOperators.cyclesAgo("billing_cycles_ago", json("{}")));
    }

    @Test
    void cyclesAgoRejectsNegativeOrNonIntegerAmount() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> CycleOperators.cyclesAgo("billing_cycles_ago", json("{\"amount\":-1}")));
        assertThrows(IllegalArgumentException.class, () -> CycleOperators.cyclesAgo("billing_cycles_ago", json("{\"amount\":1.5}")));
        assertThrows(IllegalArgumentException.class, () -> CycleOperators.cyclesAgo("billing_cycles_ago", json("{\"amount\":\"2\"}")));
    }

    @Test
    void cyclesAgoRejectsNonCycleOperator() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CycleOperators.cyclesAgo("this_month", null));
        assertTrue(e.getMessage().contains("this_month"));
    }

    @Test
    void cyclesAgoFromFilterClause() throws Exception {
        assertEquals(2, CycleOperators.cyclesAgo(new FilterClause("date", "billing_cycles_ago", json("{\"amount\":2}"))));
        assertEquals(1, CycleOperators.cyclesAgo(new FilterClause("date", "previous_billing_cycle", null)));
    }
}
