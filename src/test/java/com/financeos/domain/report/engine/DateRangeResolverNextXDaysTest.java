package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.time.AppTime;
import com.financeos.domain.report.definition.FilterClause;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The next_x_days preset: [today, today + N - 1], N a positive integer. */
class DateRangeResolverNextXDaysTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    private final DateRangeResolver resolver = new DateRangeResolver(4);

    @BeforeEach
    void fixClock() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
    }

    @AfterEach
    void resetClock() {
        AppTime.reset();
    }

    @Test
    void nextXDaysStartsTodayAndSpansNDaysInclusive() {
        DateRange r = resolver.resolveRelative("next_x_days", amount(14), TODAY);
        assertEquals(TODAY, r.from());
        assertEquals(LocalDate.of(2026, 10, 21), r.to());
        assertTrue(r.bounded());
        assertEquals(14, r.lengthDays());
    }

    @Test
    void nextOneDayIsJustToday() {
        DateRange r = resolver.resolveRelative("next_x_days", amount(1), TODAY);
        assertEquals(TODAY, r.from());
        assertEquals(TODAY, r.to());
    }

    @Test
    void nextXDaysCrossesMonthAndYearEnds() {
        DateRange r = resolver.resolveRelative("next_x_days", amount(5), LocalDate.of(2026, 12, 30));
        assertEquals(LocalDate.of(2026, 12, 30), r.from());
        assertEquals(LocalDate.of(2027, 1, 3), r.to());
    }

    @Test
    void theNoTodayOverloadResolvesAgainstAppTimeToday() {
        DateRange r = resolver.resolveRelative("next_x_days", amount(7));
        assertEquals(TODAY, r.from());
        assertEquals(LocalDate.of(2026, 10, 14), r.to());
    }

    @Test
    void effectiveRangeOfANextXDaysFilterIsTheResolvedWindow() {
        DateRange r = resolver.effectiveRange(new FilterClause("dueDate", "next_x_days", amount(30)));
        assertEquals(TODAY, r.from());
        assertEquals(LocalDate.of(2026, 11, 6), r.to());
    }

    @Test
    void zeroAmountIsRejected() {
        assertInvalid(amount(0));
    }

    @Test
    void negativeAmountIsRejected() {
        assertInvalid(amount(-3));
    }

    @Test
    void fractionalAmountIsRejected() {
        ObjectNode v = JsonNodeFactory.instance.objectNode().put("amount", 1.5);
        assertInvalid(v);
    }

    @Test
    void textAmountIsRejected() {
        ObjectNode v = JsonNodeFactory.instance.objectNode().put("amount", "7");
        assertInvalid(v);
    }

    @Test
    void missingAmountIsRejected() {
        assertInvalid(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void nullValueIsRejected() {
        assertInvalid(null);
    }

    @Test
    void scalarValueIsRejected() {
        assertInvalid(TextNode.valueOf("7"));
    }

    @Test
    void previousPeriodShiftsTheWholeWindowBackByItsLength() {
        DateRange current = resolver.resolveRelative("next_x_days", amount(7), TODAY); // Oct 8-14
        DateRange previous = resolver.previousPeriod("next_x_days", current);
        assertEquals(LocalDate.of(2026, 10, 1), previous.from());
        assertEquals(LocalDate.of(2026, 10, 7), previous.to());
    }

    @Test
    void previousPeriodOfNextOneDayIsYesterday() {
        DateRange current = resolver.resolveRelative("next_x_days", amount(1), TODAY);
        DateRange previous = resolver.previousPeriod("next_x_days", current);
        assertEquals(TODAY.minusDays(1), previous.from());
        assertEquals(TODAY.minusDays(1), previous.to());
    }

    private void assertInvalid(JsonNode value) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveRelative("next_x_days", value, TODAY));
        assertTrue(ex.getMessage().contains("next_x_days"));
    }

    private static JsonNode amount(int n) {
        return JsonNodeFactory.instance.objectNode().put("amount", n);
    }
}
