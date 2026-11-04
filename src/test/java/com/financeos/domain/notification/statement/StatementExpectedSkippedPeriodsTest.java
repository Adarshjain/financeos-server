package com.financeos.domain.notification.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.financeos.domain.statement.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The expected-statement projection with "No statement this period" skips. */
class StatementExpectedSkippedPeriodsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    /** Monthly statements closing on the 15th, the last one ending on {@code lastEnd}. */
    private static List<Statement> monthlyUntil(LocalDate lastEnd) {
        List<Statement> list = new ArrayList<>();
        for (int i = 2; i >= 0; i--) {
            Statement s = new Statement();
            s.setId(UUID.randomUUID());
            LocalDate end = lastEnd.minusMonths(i);
            s.setPeriodEnd(end);
            s.setPeriodStart(end.minusMonths(1).plusDays(1));
            s.setStatementType("credit_card");
            list.add(s);
        }
        return list;
    }

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @Test
    void withoutSkipsTheLatestOverdueCycleIsReported() {
        assertEquals(d(9, 15), StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(d(7, 15)), List.of(), TODAY));
    }

    @Test
    void aSkipAdvancesTheCursorSoTheNextCycleIsCheckedAsUsual() {
        // Statements to 15 Jul, Aug skipped: Sep 15 is still overdue, Oct 15 is inside its grace.
        assertEquals(d(9, 15), StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(d(7, 15)), List.of(d(8, 15)), TODAY));
    }

    @Test
    void skippingTheLatestOverdueCycleLeavesTheCardCurrent() {
        assertNull(StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(d(7, 15)), List.of(d(9, 15)), TODAY));
    }

    @Test
    void severalSkipsCoverEveryCycleUpToTheLatest() {
        assertNull(StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(d(7, 15)), List.of(d(8, 15), d(9, 15)), TODAY));
        // A skip with a later cycle uncovered: the cycle after the skip is reported once past grace.
        assertEquals(d(9, 15), StatementExpectedNotificationService.overduePeriodEnd(
                monthlyUntil(d(7, 15)), List.of(d(8, 15)), LocalDate.of(2026, 9, 21)));
    }

    @Test
    void aSkipFollowedByARealStatementIsIgnoredBecauseTheStatementIsLater() {
        // Statement to 15 Sep arrived after Aug was skipped: the skip is behind the cursor, nothing changes.
        assertNull(StatementExpectedNotificationService.overduePeriodEnd(monthlyUntil(d(9, 15)), List.of(d(8, 15)), TODAY));
        assertEquals(d(10, 15), StatementExpectedNotificationService.overduePeriodEnd(
                monthlyUntil(d(9, 15)), List.of(d(8, 15)), LocalDate.of(2026, 10, 21)));
    }

    @Test
    void aSkipDoesNotCreateAProjectionForACardWithoutStatements() {
        assertNull(StatementExpectedNotificationService.overduePeriodEnd(List.of(), List.of(d(9, 15)), TODAY));
    }
}
