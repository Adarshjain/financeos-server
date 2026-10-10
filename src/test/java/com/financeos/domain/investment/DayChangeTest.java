package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A position's move against the previous stored close. */
class DayChangeTest {

    private static final LocalDate LATEST = LocalDate.of(2026, 10, 8);
    private static final LocalDate PREVIOUS = LocalDate.of(2026, 10, 7);

    private static List<DayChange.PricePoint> closes(String latest, String previous) {
        return List.of(new DayChange.PricePoint(LATEST, new BigDecimal(latest)),
                new DayChange.PricePoint(PREVIOUS, new BigDecimal(previous)));
    }

    @Test
    void changeIsTheMoveTimesTheOpenQuantity() {
        DayChange c = DayChange.of(new BigDecimal("12.5"), new BigDecimal("110"), LATEST, closes("110", "100"));
        assertEquals(new BigDecimal("100"), c.previousClose());
        assertEquals(PREVIOUS, c.previousCloseAsOf());
        assertEquals(new BigDecimal("125.00"), c.dayChange());
        assertEquals(new BigDecimal("10.00"), c.dayChangePct());
        assertEquals(new BigDecimal("1250.0"), c.previousValue(new BigDecimal("12.5")));
    }

    @Test
    void aFallIsNegative() {
        DayChange c = DayChange.of(BigDecimal.ONE, new BigDecimal("97"), LATEST, closes("97", "100"));
        assertEquals(new BigDecimal("-3.00"), c.dayChange());
        assertEquals(new BigDecimal("-3.00"), c.dayChangePct());
    }

    @Test
    void aZeroPreviousCloseHasAChangeButNoPercent() {
        DayChange c = DayChange.of(BigDecimal.ONE, new BigDecimal("5"), LATEST, closes("5", "0"));
        assertEquals(new BigDecimal("5.00"), c.dayChange());
        assertNull(c.dayChangePct());
    }

    @Test
    void nothingWithoutTwoCloses() {
        assertSame(DayChange.NONE, DayChange.of(BigDecimal.ONE, BigDecimal.TEN, LATEST,
                List.of(new DayChange.PricePoint(LATEST, BigDecimal.TEN))));
        assertSame(DayChange.NONE, DayChange.of(BigDecimal.ONE, BigDecimal.TEN, LATEST, List.of()));
        assertSame(DayChange.NONE, DayChange.of(BigDecimal.ONE, BigDecimal.TEN, LATEST, null));
    }

    @Test
    void nothingForAClosedOrUnpricedPosition() {
        assertSame(DayChange.NONE, DayChange.of(BigDecimal.ZERO, BigDecimal.TEN, LATEST, closes("10", "9")));
        assertSame(DayChange.NONE, DayChange.of(null, BigDecimal.TEN, LATEST, closes("10", "9")));
        assertSame(DayChange.NONE, DayChange.of(BigDecimal.ONE, null, LATEST, closes("10", "9")));
        assertSame(DayChange.NONE, DayChange.of(BigDecimal.ONE, BigDecimal.TEN, null, closes("10", "9")));
    }

    @Test
    void nothingWhenThePositionIsNotPricedOffTheLatestRow() {
        assertSame(DayChange.NONE, DayChange.of(BigDecimal.ONE, BigDecimal.TEN, PREVIOUS, closes("10", "9")));
    }

    @Test
    void noneHasNoPreviousValue() {
        assertNull(DayChange.NONE.previousValue(BigDecimal.TEN));
    }
}
