package com.financeos.domain.investment.dividend;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DividendReceiptWindowsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    // --- base date -------------------------------------------------------------------------------

    @Test
    void baseDate_suggestedUsesExDateWhenPresent() {
        assertEquals(LocalDate.of(2026, 8, 1),
                DividendReceiptWindows.baseDate("suggested", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20)));
    }

    @Test
    void baseDate_suggestedWithoutExDateFallsBackToPayDate() {
        assertEquals(LocalDate.of(2026, 8, 20),
                DividendReceiptWindows.baseDate("suggested", null, LocalDate.of(2026, 8, 20)));
    }

    @Test
    void baseDate_importAndManualUsePayDateEvenWithExDate() {
        LocalDate ex = LocalDate.of(2026, 8, 1);
        LocalDate pay = LocalDate.of(2026, 8, 20);
        assertEquals(pay, DividendReceiptWindows.baseDate("import", ex, pay));
        assertEquals(pay, DividendReceiptWindows.baseDate("manual", ex, pay));
        assertEquals(pay, DividendReceiptWindows.baseDate(null, ex, pay));
    }

    // --- window ----------------------------------------------------------------------------------

    @Test
    void window_suggestedIsMinusThreeToPlusSixtyAroundExDate() {
        DividendReceiptWindows.Window w = DividendReceiptWindows.of("suggested", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1));
        assertEquals(LocalDate.of(2026, 7, 29), w.from());
        assertEquals(LocalDate.of(2026, 9, 30), w.to());
    }

    @Test
    void window_importIsPlusMinusFiveAroundPayDate() {
        DividendReceiptWindows.Window w = DividendReceiptWindows.of("import", null, LocalDate.of(2026, 8, 20));
        assertEquals(LocalDate.of(2026, 8, 15), w.from());
        assertEquals(LocalDate.of(2026, 8, 25), w.to());
    }

    @Test
    void window_manualNullAndUnknownSourcesArePlusMinusTenAroundPayDate() {
        for (String source : Arrays.asList("manual", null, "", "something-else")) {
            DividendReceiptWindows.Window w = DividendReceiptWindows.of(source, null, LocalDate.of(2026, 8, 20));
            assertEquals(LocalDate.of(2026, 8, 10), w.from(), "from for source " + source);
            assertEquals(LocalDate.of(2026, 8, 30), w.to(), "to for source " + source);
        }
    }

    @Test
    void window_entityOverloadDelegates() {
        Dividend d = new Dividend();
        d.setSource("import");
        d.setPayDate(LocalDate.of(2026, 8, 20));
        assertEquals(DividendReceiptWindows.of("import", null, LocalDate.of(2026, 8, 20)), DividendReceiptWindows.of(d));
        assertEquals(LocalDate.of(2026, 8, 20), DividendReceiptWindows.baseDate(d));
    }

    // --- derive ----------------------------------------------------------------------------------

    @Test
    void derive_linkedIsReceivedRegardlessOfDatesAndManualNote() {
        assertEquals(DividendReceiptStatus.received,
                DividendReceiptWindows.derive("manual", null, LocalDate.of(2020, 1, 1), true,
                        DividendReceiptStatus.not_received, TODAY, null));
    }

    @Test
    void derive_manualOverrideWinsWhenUnlinked() {
        assertEquals(DividendReceiptStatus.received_untracked,
                DividendReceiptWindows.derive("manual", null, LocalDate.of(2020, 1, 1), false,
                        DividendReceiptStatus.received_untracked, TODAY, TODAY));
        assertEquals(DividendReceiptStatus.not_received,
                DividendReceiptWindows.derive("manual", null, TODAY, false,
                        DividendReceiptStatus.not_received, TODAY, TODAY));
    }

    @Test
    void derive_windowEndingTodayOrLaterIsAwaiting() {
        // manual: window end = payDate + 10 → payDate = today − 10 ends exactly today
        assertEquals(DividendReceiptStatus.awaiting,
                DividendReceiptWindows.derive("manual", null, TODAY.minusDays(10), false, null, TODAY, TODAY));
        assertEquals(DividendReceiptStatus.awaiting,
                DividendReceiptWindows.derive("manual", null, TODAY, false, null, TODAY, TODAY));
    }

    @Test
    void derive_windowPassedAndCoveredByBankDataIsOverdue() {
        // window end = today − 1, coverage reaches today
        assertEquals(DividendReceiptStatus.overdue,
                DividendReceiptWindows.derive("manual", null, TODAY.minusDays(11), false, null, TODAY, TODAY));
        // coverage ends exactly on the window end → still covered
        assertEquals(DividendReceiptStatus.overdue,
                DividendReceiptWindows.derive("manual", null, TODAY.minusDays(11), false, null, TODAY, TODAY.minusDays(1)));
    }

    @Test
    void derive_windowPassedBeyondBankCoverageIsUnverifiable() {
        // window end = today − 1, but bank data stops at today − 2
        assertEquals(DividendReceiptStatus.unverifiable,
                DividendReceiptWindows.derive("manual", null, TODAY.minusDays(11), false, null, TODAY, TODAY.minusDays(2)));
    }

    @Test
    void derive_noBankDataIsNeverOverdue() {
        assertEquals(DividendReceiptStatus.unverifiable,
                DividendReceiptWindows.derive("manual", null, LocalDate.of(2020, 1, 1), false, null, TODAY, null));
    }

    @Test
    void derive_suggestedKeysOffExDateNotPlaceholderPayDate() {
        // ex-date 59 days ago → window still open even though payDate (= ex-date) looks stale
        LocalDate ex = TODAY.minusDays(59);
        assertEquals(DividendReceiptStatus.awaiting,
                DividendReceiptWindows.derive("suggested", ex, ex, false, null, TODAY, TODAY));
        // ex-date 61 days ago → window closed yesterday
        LocalDate exOld = TODAY.minusDays(61);
        assertEquals(DividendReceiptStatus.overdue,
                DividendReceiptWindows.derive("suggested", exOld, exOld, false, null, TODAY, TODAY));
    }

    // --- thresholds (the SQL twin of derive) ----------------------------------------------------

    @Test
    void thresholds_noCoverageUsesSentinelSoNothingIsOverdue() {
        DividendReceiptWindows.Thresholds th = DividendReceiptWindows.thresholds(TODAY, null);
        assertEquals(DividendReceiptWindows.NO_COVERAGE.minusDays(60), th.suggestedOverdueBefore());
        assertEquals(DividendReceiptWindows.NO_COVERAGE.minusDays(5), th.importOverdueBefore());
        assertEquals(DividendReceiptWindows.NO_COVERAGE.minusDays(10), th.manualOverdueBefore());
        assertEquals(TODAY.minusDays(60), th.suggestedAwaitFrom());
        assertEquals(TODAY.minusDays(5), th.importAwaitFrom());
        assertEquals(TODAY.minusDays(10), th.manualAwaitFrom());
    }

    @Test
    void thresholds_coverageInTheFutureIsCappedAtToday() {
        DividendReceiptWindows.Thresholds th = DividendReceiptWindows.thresholds(TODAY, TODAY.plusDays(30));
        assertEquals(TODAY.minusDays(10), th.manualOverdueBefore());
    }

    @Test
    void thresholds_agreeWithDeriveForEverySourceDateAndCoverage() {
        List<String> sources = Arrays.asList("suggested", "import", "manual", null);
        List<LocalDate> coverages = Arrays.asList(null, TODAY.minusDays(40), TODAY.minusDays(1), TODAY, TODAY.plusDays(3));
        for (LocalDate coverage : coverages) {
            DividendReceiptWindows.Thresholds th = DividendReceiptWindows.thresholds(TODAY, coverage);
            for (String source : sources) {
                for (int offset = -130; offset <= 15; offset++) {
                    LocalDate base = TODAY.plusDays(offset);
                    DividendReceiptStatus viaJava = DividendReceiptWindows.derive(source, base, base, false, null, TODAY, coverage);
                    DividendReceiptStatus viaSql = viaThresholds(source, base, th);
                    assertEquals(viaJava, viaSql, "source=" + source + " base=" + base + " coverage=" + coverage);
                }
            }
        }
    }

    /** Evaluates exactly what {@link DividendRepository#RECEIPT_PREDICATE} evaluates in SQL. */
    private static DividendReceiptStatus viaThresholds(String source, LocalDate base, DividendReceiptWindows.Thresholds th) {
        LocalDate awaitFrom;
        LocalDate overdueBefore;
        switch (DividendReceiptWindows.sourceOf(source)) {
            case "suggested" -> { awaitFrom = th.suggestedAwaitFrom(); overdueBefore = th.suggestedOverdueBefore(); }
            case "import" -> { awaitFrom = th.importAwaitFrom(); overdueBefore = th.importOverdueBefore(); }
            default -> { awaitFrom = th.manualAwaitFrom(); overdueBefore = th.manualOverdueBefore(); }
        }
        if (!base.isBefore(awaitFrom)) {
            return DividendReceiptStatus.awaiting;
        }
        if (base.isBefore(overdueBefore)) {
            return DividendReceiptStatus.overdue;
        }
        return DividendReceiptStatus.unverifiable;
    }
}
