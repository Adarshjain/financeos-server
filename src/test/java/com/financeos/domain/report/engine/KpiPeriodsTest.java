package com.financeos.domain.report.engine;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link KpiPeriods}: the period parameter and picking a KPI's current or previous period. */
class KpiPeriodsTest {

    private final KpiPeriods.Period current = new KpiPeriods.Period(
            List.of(new FilterClause("date", "this_month", null)),
            DateRange.of(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));
    private final KpiPeriods.Period previous = new KpiPeriods.Period(
            List.of(), DateRange.of(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));

    @Test
    void absentOrBlankPeriodMeansCurrent() {
        assertEquals(KpiPeriods.Kind.CURRENT, KpiPeriods.Kind.from(null));
        assertEquals(KpiPeriods.Kind.CURRENT, KpiPeriods.Kind.from(""));
        assertEquals(KpiPeriods.Kind.CURRENT, KpiPeriods.Kind.from("  "));
    }

    @Test
    void periodNamesAreCaseInsensitiveAndTrimmed() {
        assertEquals(KpiPeriods.Kind.CURRENT, KpiPeriods.Kind.from("current"));
        assertEquals(KpiPeriods.Kind.PREVIOUS, KpiPeriods.Kind.from("previous"));
        assertEquals(KpiPeriods.Kind.PREVIOUS, KpiPeriods.Kind.from(" PREVIOUS "));
    }

    @Test
    void anyOtherPeriodIsRejected() {
        ValidationException e = assertThrows(ValidationException.class, () -> KpiPeriods.Kind.from("last"));
        assertEquals("Invalid period 'last': expected 'current' or 'previous'", e.getMessage());
    }

    @Test
    void kindsSerializeLowerCase() {
        assertEquals("current", KpiPeriods.Kind.CURRENT.json());
        assertEquals("previous", KpiPeriods.Kind.PREVIOUS.json());
    }

    @Test
    void selectReturnsTheRequestedPeriod() {
        KpiPeriods periods = new KpiPeriods(null, current, previous);

        assertTrue(periods.previousAvailable());
        assertSame(current, periods.select(KpiPeriods.Kind.CURRENT));
        assertSame(previous, periods.select(KpiPeriods.Kind.PREVIOUS));
    }

    @Test
    void thePreviousPeriodOfAKpiWithoutOneIsRejected() {
        KpiPeriods periods = new KpiPeriods(null, current, null);

        assertFalse(periods.previousAvailable());
        assertSame(current, periods.select(KpiPeriods.Kind.CURRENT));
        ValidationException e = assertThrows(ValidationException.class, () -> periods.select(KpiPeriods.Kind.PREVIOUS));
        assertEquals("This KPI has no previous period", e.getMessage());
    }
}
