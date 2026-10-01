package com.financeos.domain.account.cycle;

import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementVerdict;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BillingCyclesTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private static Statement st(LocalDate start, LocalDate end, StatementVerdict verdict) {
        Statement s = new Statement();
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setVerdict(verdict);
        return s;
    }

    private static Statement st(LocalDate start, LocalDate end) {
        return st(start, end, StatementVerdict.AUTO_INGEST);
    }

    private static BillingCycles of(Statement... s) {
        return BillingCycles.fromStatements(Arrays.asList(s));
    }

    // ---- fromStatements ----

    @Test
    void nullStatementListMeansNoStatements() {
        BillingCycles c = BillingCycles.fromStatements(null);
        assertFalse(c.hasStatements());
    }

    @Test
    void rejectedStatementsAreIgnored() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4), StatementVerdict.REJECTED));
        assertFalse(c.hasStatements());
    }

    @Test
    void statementsWithNullDatesAreIgnored() {
        assertFalse(of(st(null, d(2026, 2, 4))).hasStatements());
        assertFalse(of(st(d(2026, 1, 5), null)).hasStatements());
    }

    @Test
    void endBeforeStartIsIgnored() {
        assertFalse(of(st(d(2026, 2, 4), d(2026, 1, 5))).hasStatements());
    }

    @Test
    void validStatementIsKept() {
        assertTrue(of(st(d(2026, 1, 5), d(2026, 2, 4))).hasStatements());
    }

    @Test
    void singleDayStatementIsValid() {
        assertTrue(of(st(d(2026, 1, 5), d(2026, 1, 5))).hasStatements());
    }

    // ---- containing: statement hit ----

    @Test
    void dateInsideStatementReturnsStatementPeriod() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        Cycle cy = c.containing(d(2026, 1, 20));
        assertEquals(d(2026, 1, 5), cy.start());
        assertEquals(d(2026, 2, 4), cy.end());
        assertEquals(Source.STATEMENT, cy.source());
        assertFalse(cy.calendarFallback());
    }

    @Test
    void statementBoundariesAreInclusive() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        assertEquals(Source.STATEMENT, c.containing(d(2026, 1, 5)).source());
        assertEquals(Source.STATEMENT, c.containing(d(2026, 2, 4)).source());
    }

    @Test
    void statementsAreSortedRegardlessOfInputOrder() {
        BillingCycles c = of(st(d(2026, 2, 5), d(2026, 3, 4)), st(d(2026, 1, 5), d(2026, 2, 4)));
        assertEquals(d(2026, 1, 5), c.containing(d(2026, 1, 10)).start());
        assertEquals(d(2026, 2, 5), c.containing(d(2026, 2, 10)).start());
    }

    // ---- containing: calendar fallback ----

    @Test
    void noStatementsFallsBackToCalendarMonth() {
        Cycle cy = BillingCycles.fromStatements(List.of()).containing(d(2026, 2, 14));
        assertEquals(d(2026, 2, 1), cy.start());
        assertEquals(d(2026, 2, 28), cy.end());
        assertEquals(Source.CALENDAR_MONTH, cy.source());
        assertTrue(cy.calendarFallback());
    }

    @Test
    void calendarFallbackHandlesLeapFebruary() {
        Cycle cy = BillingCycles.fromStatements(List.of()).containing(d(2028, 2, 29));
        assertEquals(d(2028, 2, 29), cy.end());
    }

    // ---- containing: forward projection ----

    @Test
    void forwardProjectionUsesClosingDayOfLatestStatement() {
        // Statement closes on the 4th; today is mid next cycle.
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        Cycle cy = c.containing(d(2026, 2, 20));
        assertEquals(d(2026, 2, 5), cy.start());
        assertEquals(d(2026, 3, 4), cy.end());
        assertEquals(Source.PROJECTED, cy.source());
        assertFalse(cy.calendarFallback());
    }

    @Test
    void forwardProjectionCrossesMultipleMonths() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        Cycle cy = c.containing(d(2026, 6, 3));
        assertEquals(d(2026, 5, 5), cy.start());
        assertEquals(d(2026, 6, 4), cy.end());
        assertEquals(Source.PROJECTED, cy.source());
    }

    @Test
    void forwardProjectionDayAfterStatementEndIsFirstProjectedDay() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        Cycle cy = c.containing(d(2026, 2, 5));
        assertEquals(d(2026, 2, 5), cy.start());
        assertEquals(d(2026, 3, 4), cy.end());
    }

    @Test
    void projectedEndDateItselfBelongsToThatCycle() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        Cycle cy = c.containing(d(2026, 3, 4));
        assertEquals(d(2026, 2, 5), cy.start());
        assertEquals(d(2026, 3, 4), cy.end());
    }

    @Test
    void closingDay31ClampsToShortMonthsAndRecovers() {
        BillingCycles c = of(st(d(2026, 1, 1), d(2026, 1, 31)));
        Cycle feb = c.containing(d(2026, 2, 10));
        assertEquals(d(2026, 2, 1), feb.start());
        assertEquals(d(2026, 2, 28), feb.end());
        Cycle mar = c.containing(d(2026, 3, 10));
        assertEquals(d(2026, 3, 1), mar.start());
        assertEquals(d(2026, 3, 31), mar.end());
    }

    @Test
    void closingDay31ClampsToFeb29InLeapYear() {
        BillingCycles c = of(st(d(2028, 1, 1), d(2028, 1, 31)));
        assertEquals(d(2028, 2, 29), c.containing(d(2028, 2, 10)).end());
    }

    @Test
    void forwardProjectionIsCappedBeforeNextStatement() {
        // Gap between statements: the first closes on the 4th, the next starts Feb 20.
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 2, 20), d(2026, 3, 19)));
        Cycle cy = c.containing(d(2026, 2, 10));
        assertEquals(d(2026, 2, 5), cy.start());
        assertEquals(d(2026, 2, 19), cy.end());
        assertEquals(Source.PROJECTED, cy.source());
    }

    @Test
    void forwardProjectionNotCappedWhenProjectedEndIsBeforeNextStart() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 3, 10), d(2026, 4, 9)));
        Cycle cy = c.containing(d(2026, 2, 10));
        assertEquals(d(2026, 3, 4), cy.end());
    }

    @Test
    void forwardProjectionCapWhenProjectedEndEqualsNextStart() {
        // projected end Mar 4 == next start Mar 4 -> would overlap, so cap to Mar 3
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 3, 4), d(2026, 4, 3)));
        Cycle cy = c.containing(d(2026, 2, 10));
        assertEquals(d(2026, 3, 3), cy.end());
    }

    @Test
    void usesLatestEndingStatementAsAnchor() {
        // Two statements before the date: the later one's closing day (10th) drives the projection.
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 2, 5), d(2026, 3, 10)));
        Cycle cy = c.containing(d(2026, 3, 25));
        assertEquals(d(2026, 3, 11), cy.start());
        assertEquals(d(2026, 4, 10), cy.end());
    }

    // ---- containing: backward projection ----

    @Test
    void dateBeforeAllStatementsProjectsBackwardOnOpeningDay() {
        BillingCycles c = of(st(d(2026, 3, 5), d(2026, 4, 4)));
        Cycle cy = c.containing(d(2026, 2, 20));
        assertEquals(d(2026, 2, 5), cy.start());
        assertEquals(d(2026, 3, 4), cy.end());
        assertEquals(Source.PROJECTED, cy.source());
    }

    @Test
    void backwardProjectionCrossesMultipleMonths() {
        BillingCycles c = of(st(d(2026, 3, 5), d(2026, 4, 4)));
        Cycle cy = c.containing(d(2025, 11, 7));
        assertEquals(d(2025, 11, 5), cy.start());
        assertEquals(d(2025, 12, 4), cy.end());
    }

    @Test
    void backwardProjectionDayBeforeStatementStart() {
        BillingCycles c = of(st(d(2026, 3, 5), d(2026, 4, 4)));
        Cycle cy = c.containing(d(2026, 3, 4));
        assertEquals(d(2026, 2, 5), cy.start());
        assertEquals(d(2026, 3, 4), cy.end());
    }

    @Test
    void backwardProjectionOpeningDay31Clamps() {
        BillingCycles c = of(st(d(2026, 3, 31), d(2026, 4, 29)));
        Cycle cy = c.containing(d(2026, 3, 1));
        assertEquals(d(2026, 2, 28), cy.start());
        assertEquals(d(2026, 3, 30), cy.end());
    }

    // ---- cyclesBefore ----

    @Test
    void cyclesBeforeZeroIsTheContainingCycle() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        assertEquals(c.containing(d(2026, 2, 20)), c.cyclesBefore(d(2026, 2, 20), 0));
    }

    @Test
    void cyclesBeforeOneIsThePreviousCycle() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        Cycle prev = c.cyclesBefore(d(2026, 2, 20), 1);
        assertEquals(d(2026, 1, 5), prev.start());
        assertEquals(d(2026, 2, 4), prev.end());
        assertEquals(Source.STATEMENT, prev.source());
    }

    @Test
    void cyclesBeforeWalksIntoProjectedBackwardCycles() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        Cycle two = c.cyclesBefore(d(2026, 2, 20), 2);
        assertEquals(d(2025, 12, 5), two.start());
        assertEquals(d(2026, 1, 4), two.end());
        assertEquals(Source.PROJECTED, two.source());
    }

    @Test
    void cyclesBeforeOnCalendarFallbackWalksMonths() {
        Cycle cy = BillingCycles.fromStatements(List.of()).cyclesBefore(d(2026, 3, 15), 2);
        assertEquals(d(2026, 1, 1), cy.start());
        assertEquals(d(2026, 1, 31), cy.end());
    }

    // ---- between ----

    @Test
    void betweenReturnsEmptyWhenToBeforeFrom() {
        assertTrue(of(st(d(2026, 1, 5), d(2026, 2, 4))).between(d(2026, 3, 1), d(2026, 2, 1)).isEmpty());
    }

    @Test
    void betweenListsOverlappingCyclesOldestFirst() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        List<Cycle> cycles = c.between(d(2026, 1, 20), d(2026, 3, 10));
        assertEquals(3, cycles.size());
        assertEquals(d(2026, 1, 5), cycles.get(0).start());
        assertEquals(d(2026, 2, 5), cycles.get(1).start());
        assertEquals(d(2026, 3, 5), cycles.get(2).start());
        assertEquals(d(2026, 4, 4), cycles.get(2).end());
    }

    @Test
    void betweenSingleDayReturnsOneCycle() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        assertEquals(1, c.between(d(2026, 1, 20), d(2026, 1, 20)).size());
    }

    @Test
    void betweenCycleStartingExactlyOnToIsIncluded() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)));
        List<Cycle> cycles = c.between(d(2026, 1, 20), d(2026, 2, 5));
        assertEquals(2, cycles.size());
    }

    @Test
    void betweenCyclesAreContiguousAcrossStatementGap() {
        BillingCycles c = of(st(d(2026, 1, 5), d(2026, 2, 4)), st(d(2026, 2, 20), d(2026, 3, 19)));
        List<Cycle> cycles = new ArrayList<>(c.between(d(2026, 1, 5), d(2026, 3, 19)));
        assertEquals(3, cycles.size());
        assertEquals(Source.STATEMENT, cycles.get(0).source());
        assertEquals(Source.PROJECTED, cycles.get(1).source());
        assertEquals(Source.STATEMENT, cycles.get(2).source());
        for (int i = 1; i < cycles.size(); i++) {
            assertEquals(cycles.get(i - 1).end().plusDays(1), cycles.get(i).start());
        }
    }

    // ---- Cycle ----

    @Test
    void cycleContainsIsInclusive() {
        Cycle cy = new Cycle(d(2026, 1, 5), d(2026, 2, 4), Source.PROJECTED);
        assertTrue(cy.contains(d(2026, 1, 5)));
        assertTrue(cy.contains(d(2026, 2, 4)));
        assertFalse(cy.contains(d(2026, 1, 4)));
        assertFalse(cy.contains(d(2026, 2, 5)));
    }
}
