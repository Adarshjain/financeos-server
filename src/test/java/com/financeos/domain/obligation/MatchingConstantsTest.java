package com.financeos.domain.obligation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MatchingConstantsTest {

    private static void assertTolerance(String expectedAmount, String tolerance) {
        assertEquals(0, new BigDecimal(tolerance).compareTo(MatchingConstants.loanAmountTolerance(new BigDecimal(expectedAmount))),
                "tolerance for " + expectedAmount);
    }

    @Test
    void smallEmiGetsTheHundredRupeeFloor() {
        assertTolerance("5000", "100");
    }

    @Test
    void boundaryAtTenThousandIsStillTheFloor() {
        assertTolerance("10000", "100");
    }

    @Test
    void largeEmiGetsOnePercent() {
        assertTolerance("36603", "366.03");
    }

    @Test
    void lendingToleranceIsUnchanged() {
        assertEquals(0, new BigDecimal("20").compareTo(MatchingConstants.MATCH_AMOUNT_TOLERANCE));
    }
}
