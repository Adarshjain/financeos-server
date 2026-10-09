package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;

/** Shared checks for row breakdown tests. */
final class BreakdownAssertions {

    private BreakdownAssertions() {
    }

    /**
     * The chain reconciles exactly: start + adds − subtracts equals the {@code equals} step,
     * which equals the response total; there is exactly one {@code equals} step.
     */
    static void assertReconciles(RowBreakdownResponse response) {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal equals = null;
        int equalsSteps = 0;
        for (BreakdownStep step : response.steps()) {
            switch (step.op()) {
                case "start", "add" -> sum = sum.add(step.amount());
                case "subtract" -> sum = sum.subtract(step.amount());
                case "equals" -> {
                    equals = step.amount();
                    equalsSteps++;
                }
                default -> { }
            }
        }
        assertEquals(1, equalsSteps, "exactly one equals step");
        assertNotNull(equals);
        assertEquals(0, sum.compareTo(equals), "chain " + sum + " != equals " + equals);
        assertEquals(0, equals.compareTo(response.total()), "equals " + equals + " != total " + response.total());
    }
}
