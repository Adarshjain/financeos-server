package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateRangeResolverTest {

    /** April fiscal-year start, matching the production default. */
    private final DateRangeResolver resolver = new DateRangeResolver(4);

    @Test
    void thisMonthResolvesToCalendarMonth() {
        DateRange r = resolver.resolveRelative("this_month", null, LocalDate.of(2026, 6, 15));
        assertEquals(LocalDate.of(2026, 6, 1), r.from());
        assertEquals(LocalDate.of(2026, 6, 30), r.to());
        assertTrue(r.bounded());
    }

    @Test
    void lastXDaysIsRollingInclusiveOfToday() {
        DateRange r = resolver.resolveRelative("last_x_days", amount(7), LocalDate.of(2026, 6, 15));
        assertEquals(LocalDate.of(2026, 6, 9), r.from());
        assertEquals(LocalDate.of(2026, 6, 15), r.to());
    }

    @Test
    void currentFinancialYearUsesAprilStart() {
        // January 2026 falls in the FY that started April 2025.
        DateRange r = resolver.resolveRelative("current_fy", null, LocalDate.of(2026, 1, 10));
        assertEquals(LocalDate.of(2025, 4, 1), r.from());
        assertEquals(LocalDate.of(2026, 3, 31), r.to());
    }

    @Test
    void allTimeIsUnbounded() {
        DateRange r = resolver.resolveRelative("all_time", null, LocalDate.of(2026, 6, 15));
        assertFalse(r.bounded());
    }

    @Test
    void previousPeriodOfThisMonthIsTheCalendarMonthBefore() {
        DateRange current = resolver.resolveRelative("this_month", null, LocalDate.of(2026, 6, 15));
        DateRange previous = resolver.previousPeriod("this_month", current);
        assertEquals(LocalDate.of(2026, 5, 1), previous.from());
        assertEquals(LocalDate.of(2026, 5, 31), previous.to());
    }

    @Test
    void previousPeriodOfLastXDaysShiftsByLength() {
        DateRange current = resolver.resolveRelative("last_x_days", amount(7), LocalDate.of(2026, 6, 15)); // Jun 9-15
        DateRange previous = resolver.previousPeriod("last_x_days", current);
        assertEquals(LocalDate.of(2026, 6, 2), previous.from());
        assertEquals(LocalDate.of(2026, 6, 8), previous.to());
    }

    private static JsonNode amount(int n) {
        return JsonNodeFactory.instance.objectNode().put("amount", n);
    }
}
