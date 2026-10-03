package com.financeos.domain.account.cycle;

import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementVerdict;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round 2: de-overlap on load, the closing-day rule, the unified projection and calendarMonths(). */
class BillingCyclesRound2Test {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private static Statement st(LocalDate start, LocalDate end) {
        Statement s = new Statement();
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        return s;
    }

    private static BillingCycles of(Statement... s) {
        return BillingCycles.fromStatements(Arrays.asList(s));
    }

    private static void assertCycle(Cycle c, LocalDate start, LocalDate end, Source source) {
        assertEquals(start, c.start(), "start");
        assertEquals(end, c.end(), "end");
        assertEquals(source, c.source(), "source");
    }

    // ---- de-overlap ----

    @Test
    void overlappingEarlierStatementIsTrimmedToTheDayBeforeTheLaterOne() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 10)), st(d(2026, 2, 5), d(2026, 3, 4)));

        assertCycle(c.containing(d(2026, 1, 20)), d(2026, 1, 5), d(2026, 2, 4), Source.STATEMENT);
        // the overlapping days belong to the later statement only
        assertCycle(c.containing(d(2026, 2, 7)), d(2026, 2, 5), d(2026, 3, 4), Source.STATEMENT);
        assertEquals(2, c.between(d(2026, 1, 5), d(2026, 3, 4)).size());
    }

    @Test
    void overlapIsResolvedRegardlessOfInputOrder() {
        BillingCycles c = of(st(d(2026, 2, 5), d(2026, 3, 4)), st(d(2026, 1, 5), d(2026, 2, 10)));
        assertCycle(c.containing(d(2026, 1, 20)), d(2026, 1, 5), d(2026, 2, 4), Source.STATEMENT);
        assertCycle(c.containing(d(2026, 2, 7)), d(2026, 2, 5), d(2026, 3, 4), Source.STATEMENT);
    }

    @Test
    void statementTrimmedToNothingIsDropped() {
        // same start: the shorter one is entirely covered by the longer one and disappears
        BillingCycles c = of(st(d(2026, 2, 5), d(2026, 2, 20)), st(d(2026, 2, 5), d(2026, 3, 4)));

        assertCycle(c.containing(d(2026, 2, 10)), d(2026, 2, 5), d(2026, 3, 4), Source.STATEMENT);
        assertEquals(1, c.between(d(2026, 2, 5), d(2026, 3, 4)).size());
    }

    @Test
    void identicalDuplicateStatementsCollapseToOne() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 1, 5), d(2026, 2, 4)));

        assertEquals(1, c.between(d(2026, 1, 5), d(2026, 2, 4)).size());
        assertCycle(c.containing(d(2026, 1, 20)), d(2026, 1, 5), d(2026, 2, 4), Source.STATEMENT);
    }

    @Test
    void threeWayOverlapLeavesEachDateInExactlyOnePeriod() {
        BillingCycles c = of(st(d(2026, 1, 1), d(2026, 1, 31)), st(d(2026, 1, 10), d(2026, 2, 20)),
                st(d(2026, 1, 20), d(2026, 3, 5)));

        assertCycle(c.containing(d(2026, 1, 5)), d(2026, 1, 1), d(2026, 1, 9), Source.STATEMENT);
        assertCycle(c.containing(d(2026, 1, 15)), d(2026, 1, 10), d(2026, 1, 19), Source.STATEMENT);
        assertCycle(c.containing(d(2026, 2, 10)), d(2026, 1, 20), d(2026, 3, 5), Source.STATEMENT);
    }

    @Test
    void cascadingOverlapDropsTheCoveredMiddleStatementAndTrimsTheFirst() {
        BillingCycles c = of(st(d(2026, 1, 1), d(2026, 1, 10)), st(d(2026, 1, 5), d(2026, 1, 20)),
                st(d(2026, 1, 5), d(2026, 1, 30)));

        assertCycle(c.containing(d(2026, 1, 3)), d(2026, 1, 1), d(2026, 1, 4), Source.STATEMENT);
        assertCycle(c.containing(d(2026, 1, 15)), d(2026, 1, 5), d(2026, 1, 30), Source.STATEMENT);
    }

    @Test
    void adjacentStatementsAreNotTrimmed() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 2, 5), d(2026, 3, 4)));
        assertCycle(c.containing(d(2026, 2, 4)), d(2026, 1, 5), d(2026, 2, 4), Source.STATEMENT);
        assertCycle(c.containing(d(2026, 2, 5)), d(2026, 2, 5), d(2026, 3, 4), Source.STATEMENT);
    }

    // ---- closing day ----

    @Test
    void normalClosingDayIsTheStatementEndDay() {
        BillingCycles c = of(st(d(2026, 1, 16), d(2026, 2, 15)));
        assertCycle(c.containing(d(2026, 4, 1)), d(2026, 3, 16), d(2026, 4, 15), Source.PROJECTED);
    }

    @Test
    void february28WithA30thClosingHistoryKeepsClosingDay30() {
        BillingCycles c = of(st(d(2026, 1, 1), d(2026, 1, 30)), st(d(2026, 1, 31), d(2026, 2, 28)));

        assertCycle(c.containing(d(2026, 3, 10)), d(2026, 3, 1), d(2026, 3, 30), Source.PROJECTED);
        assertCycle(c.containing(d(2026, 4, 10)), d(2026, 3, 31), d(2026, 4, 30), Source.PROJECTED);
    }

    @Test
    void allMonthEndStatementsMeanClosingDay31() {
        BillingCycles c = of(st(d(2025, 12, 1), d(2025, 12, 31)), st(d(2026, 1, 1), d(2026, 1, 31)),
                st(d(2026, 2, 1), d(2026, 2, 28)));

        assertCycle(c.containing(d(2026, 3, 10)), d(2026, 3, 1), d(2026, 3, 31), Source.PROJECTED);
        assertCycle(c.containing(d(2026, 4, 10)), d(2026, 4, 1), d(2026, 4, 30), Source.PROJECTED);
    }

    @Test
    void singleFebruary28StatementMeansClosingDay31() {
        BillingCycles c = of(st(d(2026, 2, 1), d(2026, 2, 28)));
        assertCycle(c.containing(d(2026, 3, 10)), d(2026, 3, 1), d(2026, 3, 31), Source.PROJECTED);
    }

    @Test
    void april30WithA31stHistoryMeansClosingDay31() {
        BillingCycles c = of(st(d(2026, 3, 1), d(2026, 3, 31)), st(d(2026, 4, 1), d(2026, 4, 30)));
        assertCycle(c.containing(d(2026, 5, 10)), d(2026, 5, 1), d(2026, 5, 31), Source.PROJECTED);
    }

    @Test
    void closingDayLookbackIsTheLastThreeStatements() {
        // Nov 30 is outside the window of the last three (Dec 15, Jan 15, Feb 28): closing day 28, not 30
        BillingCycles c = of(st(d(2025, 11, 1), d(2025, 11, 30)), st(d(2025, 12, 1), d(2025, 12, 15)),
                st(d(2026, 1, 1), d(2026, 1, 15)), st(d(2026, 2, 1), d(2026, 2, 28)));

        assertCycle(c.containing(d(2026, 3, 10)), d(2026, 3, 1), d(2026, 3, 28), Source.PROJECTED);
    }

    @Test
    void closingDayAnchorsOnTheNearestLaterStatementWhenNothingPrecedes() {
        // a lone Apr 30 statement (a month end) projects backwards as a month-end card: Feb 1 - Feb 28
        BillingCycles c = of(st(d(2026, 4, 1), d(2026, 4, 30)));
        assertCycle(c.containing(d(2026, 2, 10)), d(2026, 2, 1), d(2026, 2, 28), Source.PROJECTED);
    }

    // ---- unified projection ----

    @Test
    void forwardProjectionAfterTheLatestStatement() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        assertCycle(c.containing(d(2026, 2, 20)), d(2026, 2, 5), d(2026, 3, 4), Source.PROJECTED);
    }

    @Test
    void backwardProjectionBeforeTheEarliestStatementUsesItsClosingDay() {
        BillingCycles c = of(st(d(2026, 3, 5), d(2026, 4, 4)));
        assertCycle(c.containing(d(2026, 1, 20)), d(2026, 1, 5), d(2026, 2, 4), Source.PROJECTED);
        assertCycle(c.containing(d(2026, 2, 20)), d(2026, 2, 5), d(2026, 3, 4), Source.PROJECTED);
    }

    @Test
    void projectionInAGapBetweenStatements() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 4, 5), d(2026, 5, 4)));
        assertCycle(c.containing(d(2026, 2, 20)), d(2026, 2, 5), d(2026, 3, 4), Source.PROJECTED);
        assertCycle(c.containing(d(2026, 3, 10)), d(2026, 3, 5), d(2026, 4, 4), Source.PROJECTED);
    }

    @Test
    void partialFirstStatementLeavesAStubCycleBeforeIt() {
        // card opened mid-cycle: the statement runs Jan 20 - Feb 4 although the card closes on the 4th
        BillingCycles c = of(st(d(2026, 1, 20), d(2026, 2, 4)));

        assertCycle(c.containing(d(2026, 1, 10)), d(2026, 1, 5), d(2026, 1, 19), Source.PROJECTED);
        assertCycle(c.containing(d(2025, 12, 20)), d(2025, 12, 5), d(2026, 1, 4), Source.PROJECTED);
        List<Cycle> cycles = c.between(d(2025, 12, 20), d(2026, 2, 4));
        assertEquals(3, cycles.size());
        for (int i = 1; i < cycles.size(); i++) {
            assertEquals(cycles.get(i - 1).end().plusDays(1), cycles.get(i).start());
        }
    }

    @Test
    void projectionEndIsClampedBeforeTheNextStatement() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 2, 20), d(2026, 3, 19)));
        assertCycle(c.containing(d(2026, 2, 10)), d(2026, 2, 5), d(2026, 2, 19), Source.PROJECTED);
    }

    @Test
    void projectionBetweenTwoStatementsIsBoundedOnBothSides() {
        // starts the day after the earlier statement and ends the day before the later one
        BillingCycles both = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 2, 12), d(2026, 3, 4)));
        assertCycle(both.containing(d(2026, 2, 8)), d(2026, 2, 5), d(2026, 2, 11), Source.PROJECTED);
    }

    @Test
    void betweenStaysContiguousAndNonOverlappingAcrossStubsAndOverlaps() {
        BillingCycles c = of(st(d(2026, 1, 20), d(2026, 2, 10)), st(d(2026, 2, 5), d(2026, 3, 4)));
        List<Cycle> cycles = c.between(d(2025, 12, 1), d(2026, 5, 1));
        for (int i = 1; i < cycles.size(); i++) {
            assertEquals(cycles.get(i - 1).end().plusDays(1), cycles.get(i).start(), "cycle " + i);
        }
    }

    // ---- calendarMonths ----

    @Test
    void calendarMonthsHasNoStatementsAndUsesTheCalendarMonth() {
        BillingCycles c = BillingCycles.calendarMonths();
        assertFalse(c.hasStatements());
        Cycle cy = c.containing(d(2026, 2, 14));
        assertCycle(cy, d(2026, 2, 1), d(2026, 2, 28), Source.CALENDAR_MONTH);
        assertTrue(cy.calendarFallback());
    }

    @Test
    void calendarMonthsWalkBackwardAndHandleLeapFebruary() {
        BillingCycles c = BillingCycles.calendarMonths();
        assertCycle(c.cyclesBefore(d(2026, 3, 15), 2), d(2026, 1, 1), d(2026, 1, 31), Source.CALENDAR_MONTH);
        assertEquals(d(2028, 2, 29), c.containing(d(2028, 2, 3)).end());
        assertEquals(3, c.between(d(2026, 1, 20), d(2026, 3, 2)).size());
    }
}
