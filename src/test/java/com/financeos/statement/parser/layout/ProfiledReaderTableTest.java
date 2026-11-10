package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.Order;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Header detection, column assignment, dates, continuation lines, end markers, skip lines, sections, order, unread lines. */
class ProfiledReaderTableTest {

    private static List<RowResult> rows(List<Line> lines, LayoutProfile p) {
        return ProfiledReader.read(lines, p).outcome().results();
    }

    // ---- header ----

    @Test
    void headerLineIsFoundWhenAllLabelsAppearInOrder() {
        assertTrue(ProfiledReader.hasHeader(List.of(bankHeader(0, 100, 0)), bankProfile()));
    }

    @Test
    void headerWithLabelsOutOfOrderIsNotAHeader() {
        Line swapped = ln(0, 100, "Date", 50, "Narration", 120, "Credit", 330, "Debit", 400, "Balance", 470);
        assertFalse(ProfiledReader.hasHeader(List.of(swapped), bankProfile()));
    }

    @Test
    void headerWithAMissingLabelIsNotAHeader() {
        Line partial = ln(0, 100, "Date", 50, "Narration", 120, "Debit", 330, "Credit", 400);
        assertFalse(ProfiledReader.hasHeader(List.of(partial), bankProfile()));
    }

    @Test
    void headerAbsentMeansNoMatchAndNoRows() {
        List<Line> lines = List.of(bankRow(0, 120, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        assertFalse(ProfiledReader.hasHeader(lines, bankProfile()));
        assertThrows(IllegalStateException.class, () -> ProfiledReader.read(lines, bankProfile()));
    }

    @Test
    void aLineWithTheRejectedWordIsNotTheHeader() {
        LayoutProfile p = bank("t").headerRejects("cashback").build();
        Line wide = ln(0, 100, "Date", 50, "Narration", 120, "Debit", 330, "Credit", 400, "Balance", 470, "Cashback", 520);
        assertFalse(ProfiledReader.hasHeader(List.of(wide), p));
        assertTrue(ProfiledReader.hasHeader(List.of(bankHeader(0, 100, 0)), p));
    }

    @Test
    void headerRepeatedOnPageTwoStartsTheTableThere() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", "10.00", null, "90.00"),
                bankHeader(1, 60, 0),
                bankRow(1, 70, 0, "06/01/2026", "SHOP TWO", "5.00", null, "85.00"));
        List<RowResult> r = rows(lines, bankProfile());
        assertEquals(2, r.size());
        assertEquals("SHOP TWO", r.get(1).description);
    }

    @Test
    void aPageWithoutTheHeaderContinuesTheTableByDefault() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", "10.00", null, "90.00"),
                bankRow(1, 70, 0, "06/01/2026", "PAGE TWO", "5.00", null, "85.00"));
        var res = ProfiledReader.read(lines, bankProfile());
        assertEquals(2, res.outcome().results().size());
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aPageWithoutTheHeaderUsesThePreviousPagesGeometry() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 18),
                bankRow(0, 110, 18, "05/01/2026", "SHOP ONE", "10.00", null, "90.00"),
                bankRow(1, 70, 18, "06/01/2026", "SHIFTED TWO", null, "25.00", "115.00"));
        List<RowResult> r = rows(lines, bankProfile());
        assertEquals(2, r.size());
        assertEquals(25.00, r.get(1).amount, 0.001);
    }

    @Test
    void withoutContinuationAPageWithoutTheHeaderIsNotTableAndItsRowsAreCountedUnread() {
        LayoutProfile p = bank("nocont").continuesWithoutHeader(false).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", "10.00", null, "90.00"),
                bankRow(1, 70, 0, "06/01/2026", "NOT TABLE", "5.00", null, "85.00"));
        var res = ProfiledReader.read(lines, p);
        assertEquals(1, res.outcome().results().size());
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void aPageEndMarkerEndsOnlyThatPageAndTheNextHeaderlessPageContinues() {
        LayoutProfile p = bank("pageend").endMarker("continued", EndScope.PAGE).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"),
                ln(0, 120, "Continued", 200),
                bankRow(1, 70, 0, "06/01/2026", "TWO", "5.00", null, "85.00"));
        assertEquals(2, rows(lines, p).size());
    }

    @Test
    void aLineAfterAPageEndMarkerWithAnAmountOrADateIsUnread() {
        LayoutProfile p = bank("pageend").endMarker("continued", EndScope.PAGE).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"),
                ln(0, 120, "Continued", 200),
                ln(0, 130, "Subtotal", 120, "9.00", 330),
                ln(0, 140, "07/01/2026", 50, "words only", 120));
        assertEquals(2, ProfiledReader.read(lines, p).unreadAmountLines());
    }

    @Test
    void aLineAboveTheRepeatedHeaderWithAnAmountIsUnread() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"),
                ln(1, 40, "Brought", 120, "forward", 170, "90.00", 470),
                bankHeader(1, 60, 0),
                bankRow(1, 70, 0, "06/01/2026", "TWO", "5.00", null, "85.00"));
        assertEquals(1, ProfiledReader.read(lines, bankProfile()).unreadAmountLines());
    }

    @Test
    void linesAfterTheForGoodEndAreNeverCounted() {
        LayoutProfile p = bank("end").endMarker("end of statement", EndScope.FOR_GOOD).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"),
                ln(0, 120, "End", 200, "of", 230, "Statement", 250),
                ln(0, 130, "Terms", 120, "9,999.00", 330),
                ln(1, 60, "More", 120, "terms", 170, "8,888.00", 330));
        assertEquals(0, ProfiledReader.read(lines, p).unreadAmountLines());
    }

    @Test
    void linesAboveTheHeaderAreNotRows() {
        List<Line> lines = List.of(
                bankRow(0, 50, 0, "01/01/2026", "ABOVE THE TABLE", "1.00", null, "99.00"),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "INSIDE", "10.00", null, "90.00"));
        List<RowResult> r = rows(lines, bankProfile());
        assertEquals(1, r.size());
        assertEquals("INSIDE", r.get(0).description);
    }

    // ---- columns ----

    @Test
    void amountsGoToTheColumnTheirXPositionFallsIn() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "25.00", "115.00"));
        List<RowResult> r = rows(lines, bankProfile());
        assertEquals(-10.00, r.get(0).amount, 0.001);
        assertEquals(25.00, r.get(1).amount, 0.001);
        assertEquals(115.00, r.get(1).balance, 0.001);
    }

    @Test
    void aPageShiftedRightStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "10.00", null, "90.00"),
                bankHeader(1, 60, 18),
                bankRow(1, 70, 18, "06/01/2026", "SHIFTED", null, "25.00", "115.00"));
        List<RowResult> r = rows(lines, bankProfile());
        assertEquals(2, r.size());
        assertEquals("SHIFTED", r.get(1).description);
        assertEquals(25.00, r.get(1).amount, 0.001);
        assertEquals(115.00, r.get(1).balance, 0.001);
    }

    @Test
    void absoluteColumnsDoNotMoveWithTheHeader() {
        LayoutProfile p = LayoutProfile.builder("abs")
                .product(LayoutProfile.Product.BANK)
                .headerLabel("DATE", "\\bdate\\b")
                .headerLabel("AMT", "\\bamount\\b")
                .absoluteColumn(LayoutProfile.Role.DATE, 40, 110)
                .absoluteColumn(LayoutProfile.Role.NARRATION, 110, 380)
                .absoluteColumn(LayoutProfile.Role.AMOUNT, 380, 450)
                .sign(LayoutProfile.Sign.MINUS_IS_CREDIT)
                .build();
        List<Line> lines = List.of(
                ln(0, 100, "Date", 60, "Amount", 400),
                ln(0, 110, "05/01/2026", 50, "SHOP", 120, "10.00", 400));
        assertEquals(-10.00, rows(lines, p).get(0).amount, 0.001);
    }

    @Test
    void wordsInNoColumnAreIgnored() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "SHOP", 120, "10.00", 330, "90.00", 470, "margin", 590));
        assertEquals("SHOP", rows(lines, bankProfile()).get(0).description);
    }

    @Test
    void anAmountInNoColumnCountsAsUnread() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "SHOP", 120, "10.00", 330, "90.00", 470, "7.00", 590));
        assertEquals(1, ProfiledReader.read(lines, bankProfile()).unreadAmountLines());
    }

    @Test
    void narrationWordsOfSeveralColumnsAreJoinedInOrder() {
        LayoutProfile p = bank("two").fromLabel(LayoutProfile.Role.NARRATION, "NARR", -10, 100)
                .fromLabel(LayoutProfile.Role.NARRATION, "NARR", 100, 200).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "FIRST", 120, "SECOND", 240, "10.00", 330, "90.00", 470));
        assertEquals("FIRST SECOND", rows(lines, p).get(0).description);
    }

    @Test
    void aDatedLineWithoutAnAmountIsDroppedNotRead() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "NO AMOUNT", null, null, null),
                bankRow(0, 120, 0, "06/01/2026", "FINE", "10.00", null, "90.00"));
        var out = ProfiledReader.read(lines, bankProfile()).outcome();
        assertEquals(1, out.results().size());
        assertEquals(1, out.rowsDropped());
    }

    @Test
    void aDatedLineWithBothDebitAndCreditIsDropped() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "BOTH", "10.00", "10.00", "90.00"),
                bankRow(0, 120, 0, "06/01/2026", "FINE", "10.00", null, "80.00"));
        var out = ProfiledReader.read(lines, bankProfile()).outcome();
        assertEquals(1, out.results().size());
        assertEquals(1, out.rowsDropped());
    }

    // ---- dates ----

    @Test
    void aLineWhoseDateDoesNotParseIsNotARow() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "31/02/2026", "IMPOSSIBLE DATE", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "06/01/2026", "FINE", "10.00", null, "80.00"));
        assertEquals(1, rows(lines, bankProfile()).size());
    }

    @Test
    void severalDateFormatsAreTriedInOrder() {
        LayoutProfile p = bank("fmts").dateFormats("dd/MM/yyyy", "dd-MMM-yy").build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "A", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "06-Jan-26", "B", "10.00", null, "80.00"));
        List<RowResult> r = rows(lines, p);
        assertEquals(LocalDate.of(2026, 1, 5), r.get(0).date);
        assertEquals(LocalDate.of(2026, 1, 6), r.get(1).date);
    }

    private static LayoutProfile yearless() {
        return bank("yearless").dateFormats("dd/MM/yyyy", "dd MMM")
                .summary(LayoutProfile.MetaTarget.PERIOD, "statement\\s+period")
                .type(LayoutProfile.ValueType.DATE_RANGE).rightOnLine().done()
                .build();
    }

    @Test
    void yearlessDatesTakeTheirYearFromAPeriodSpanningDecemberToJanuary() {
        List<Line> lines = List.of(
                ln(0, 50, "Statement", 40, "Period", 100, "20/12/2025", 150, "-", 220, "19/01/2026", 230),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "28 Dec", "LATE DEC", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "03 Jan", "EARLY JAN", "10.00", null, "80.00"));
        List<RowResult> r = rows(lines, yearless());
        assertEquals(LocalDate.of(2025, 12, 28), r.get(0).date);
        assertEquals(LocalDate.of(2026, 1, 3), r.get(1).date);
    }

    @Test
    void yearlessTwentyNinthOfFebruaryResolvesToTheLeapYear() {
        List<Line> lines = List.of(
                ln(0, 50, "Statement", 40, "Period", 100, "20/01/2024", 150, "-", 220, "19/03/2024", 230),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "29 Feb", "LEAP DAY", "10.00", null, "90.00"));
        assertEquals(LocalDate.of(2024, 2, 29), rows(lines, yearless()).get(0).date);
    }

    @Test
    void twentyNinthOfFebruaryNeverLandsInANonLeapYear() {
        LocalDate d = DateResolver.resolveYear(MonthDay.of(2, 29), LocalDate.of(2023, 12, 20), LocalDate.of(2024, 3, 19));
        assertEquals(LocalDate.of(2024, 2, 29), d);
        // a period wholly in a non-leap year: the nearest leap-year 29 Feb, never an invalid date
        LocalDate near = DateResolver.resolveYear(MonthDay.of(2, 29), LocalDate.of(2025, 2, 10), LocalDate.of(2025, 3, 9));
        assertEquals(LocalDate.of(2024, 2, 29), near);
    }

    @Test
    void yearlessDateOutsideThePeriodTakesTheNearestYearAdjacentToIt() {
        LocalDate d = DateResolver.resolveYear(MonthDay.of(1, 2), LocalDate.of(2025, 12, 20), LocalDate.of(2026, 1, 1));
        assertEquals(LocalDate.of(2026, 1, 2), d);
    }

    @Test
    void yearlessDateWithoutAPeriodIsNotARow() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "28 Dec", "NO PERIOD", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "29/12/2025", "FULL DATE", "10.00", null, "80.00"));
        List<RowResult> r = rows(lines, yearless());
        assertEquals(1, r.size());
        assertEquals("FULL DATE", r.get(0).description);
    }

    // ---- continuation lines ----

    private static LayoutProfile continued(double below, double above) {
        return bank("cont").continuation(below, above).build();
    }

    private static Line narrationOnly(double top, String text) {
        return ln(0, top, text, NARR);
    }

    @Test
    void aNarrationLineBelowARowJoinsItWithinTheGap() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "UPI SHOP", "10.00", null, "90.00"),
                narrationOnly(118, "REF ONE"),
                bankRow(0, 140, 0, "06/01/2026", "NEXT", "5.00", null, "85.00"));
        List<RowResult> r = rows(lines, continued(10, 0));
        assertEquals("UPI SHOP REF ONE", r.get(0).description);
        assertEquals("NEXT", r.get(1).description);
    }

    @Test
    void aNarrationLineAboveARowJoinsItWithinTheGap() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "FIRST", "10.00", null, "90.00"),
                narrationOnly(142, "PREFIX TEXT"),
                bankRow(0, 150, 0, "06/01/2026", "SECOND", "5.00", null, "85.00"));
        List<RowResult> r = rows(lines, continued(10, 10));
        assertEquals("FIRST", r.get(0).description);
        assertEquals("PREFIX TEXT SECOND", r.get(1).description);
    }

    @Test
    void aNarrationLineBeyondTheMaximumGapJoinsNoRow() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "FIRST", "10.00", null, "90.00"),
                narrationOnly(125, "TOO FAR"),
                bankRow(0, 160, 0, "06/01/2026", "SECOND", "5.00", null, "85.00"));
        List<RowResult> r = rows(lines, continued(10, 10));
        assertEquals("FIRST", r.get(0).description);
        assertEquals("SECOND", r.get(1).description);
    }

    @Test
    void theNearerRowWinsWhenBothGapsQualify() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "FIRST", "10.00", null, "90.00"),
                narrationOnly(130, "MIDDLE"),
                bankRow(0, 136, 0, "06/01/2026", "SECOND", "5.00", null, "85.00"));
        List<RowResult> r = rows(lines, continued(30, 30));
        assertEquals("FIRST", r.get(0).description);
        assertEquals("MIDDLE SECOND", r.get(1).description);
    }

    @Test
    void continuationLinesAreOffWhenNoGapIsConfigured() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "FIRST", "10.00", null, "90.00"),
                narrationOnly(118, "STRAY"));
        assertEquals("FIRST", rows(lines, bankProfile()).get(0).description);
    }

    @Test
    void aContinuationLineNeverJoinsARowOnAnotherPage() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "FIRST", "10.00", null, "90.00"),
                bankHeader(1, 100, 0),
                ln(1, 104, "TOP OF PAGE TWO", NARR),
                bankRow(1, 110, 0, "06/01/2026", "SECOND", "5.00", null, "85.00"));
        List<RowResult> r = rows(lines, continued(10, 0));
        assertEquals("FIRST", r.get(0).description);
        assertEquals("SECOND", r.get(1).description);
    }

    @Test
    void aUnattachedLineWithAnAmountInTheNarrationColumnIsUnread() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "FIRST", "10.00", null, "90.00"),
                ln(0, 160, "Reward", NARR, "balance", 160, "12,345.00", 220));
        assertEquals(1, ProfiledReader.read(lines, continued(10, 10)).unreadAmountLines());
    }

    // ---- end markers, skip lines, sections ----

    @Test
    void aForGoodEndMarkerStopsTheTableOnEveryLaterPage() {
        LayoutProfile p = bank("end").endMarker("end of statement", EndScope.FOR_GOOD).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "KEPT", "10.00", null, "90.00"),
                ln(0, 120, "End", 200, "of", 230, "Statement", 250),
                bankRow(0, 130, 0, "06/01/2026", "AFTER MARKER", "5.00", null, "85.00"),
                bankHeader(1, 60, 0),
                bankRow(1, 70, 0, "07/01/2026", "NEXT PAGE", "5.00", null, "80.00"));
        List<RowResult> r = rows(lines, p);
        assertEquals(1, r.size());
        assertEquals("KEPT", r.get(0).description);
    }

    @Test
    void aPageEndMarkerStopsOnlyThatPage() {
        LayoutProfile p = bank("end").endMarker("continued on next page", EndScope.PAGE).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "KEPT", "10.00", null, "90.00"),
                ln(0, 120, "Continued", 200, "on", 250, "next", 270, "page", 300),
                bankRow(0, 130, 0, "06/01/2026", "DROPPED", "5.00", null, "85.00"),
                bankHeader(1, 60, 0),
                bankRow(1, 70, 0, "07/01/2026", "PAGE TWO", "5.00", null, "80.00"));
        List<RowResult> r = rows(lines, p);
        assertEquals(2, r.size());
        assertEquals("PAGE TWO", r.get(1).description);
    }

    @Test
    void anEndMarkerLineIsNotCountedAsUnread() {
        LayoutProfile p = bank("end").endMarker("end of statement", EndScope.FOR_GOOD).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "KEPT", "10.00", null, "90.00"),
                ln(0, 120, "End", 200, "of", 230, "Statement", 250, "1,000.00", 330));
        assertEquals(0, ProfiledReader.read(lines, p).unreadAmountLines());
    }

    @Test
    void skipLinesAreFurnitureAndNeverRowsOrUnread() {
        LayoutProfile p = bank("skip").skipLine("^page \\d+").build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "KEPT", "10.00", null, "90.00"),
                ln(0, 120, "Page", 50, "1", 80, "1,234.00", 330));
        var res = ProfiledReader.read(lines, p);
        assertEquals(1, res.outcome().results().size());
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aSectionHeaderSetsTheCardLast4OfTheRowsThatFollow() {
        LayoutProfile p = bank("sec").sectionHeader("card ending (\\d{4})").build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 106, "Card", 50, "ending", 80, "1111", 120),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"),
                ln(0, 116, "Card", 50, "ending", 80, "2222", 120),
                bankRow(0, 120, 0, "06/01/2026", "TWO", "10.00", null, "80.00"));
        List<RowResult> r = rows(lines, p);
        assertEquals("1111", r.get(0).cardLast4);
        assertEquals("2222", r.get(1).cardLast4);
    }

    @Test
    void rowsBeforeAnySectionHeaderHaveNoCardLast4() {
        LayoutProfile p = bank("sec").sectionHeader("card ending (\\d{4})").build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"));
        assertNull(rows(lines, p).get(0).cardLast4);
    }

    // ---- order ----

    private static List<Line> newestFirst() {
        return List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "07/01/2026", "THIRD", "10.00", null, "80.00"),
                bankRow(0, 120, 0, "06/01/2026", "SECOND", "10.00", null, "90.00"),
                bankRow(0, 130, 0, "05/01/2026", "FIRST", null, "100.00", "100.00"));
    }

    @Test
    void descOrderIsReversedToChronological() {
        List<RowResult> r = rows(newestFirst(), bank("desc").order(Order.DESC).build());
        assertEquals("FIRST", r.get(0).description);
        assertEquals("THIRD", r.get(2).description);
    }

    @Test
    void ascOrderIsKept() {
        List<RowResult> r = rows(newestFirst(), bank("asc").order(Order.ASC).build());
        assertEquals("THIRD", r.get(0).description);
    }

    @Test
    void autoOrderReversesWhenTheDatesRunBackwards() {
        List<RowResult> r = rows(newestFirst(), bank("auto").order(Order.AUTO).build());
        assertEquals("FIRST", r.get(0).description);
    }

    @Test
    void autoOrderKeepsRowsThatAlreadyRunForwards() {
        List<Line> forward = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "FIRST", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "06/01/2026", "SECOND", "10.00", null, "80.00"));
        assertEquals("FIRST", rows(forward, bank("auto").order(Order.AUTO).build()).get(0).description);
    }

    // ---- unread amount lines ----

    @Test
    void anAmountLineThatIsNeitherRowNorContinuationNorMarkerNorSkipIsCounted() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "KEPT", "10.00", null, "90.00"),
                ln(0, 120, "Subtotal", 50, "1,000.00", 330),
                ln(0, 130, "Carried", 50, "forward", 90, "2,000.00", 470));
        var res = ProfiledReader.read(lines, bankProfile());
        assertEquals(2, res.unreadAmountLines());
        assertEquals(2, res.outcome().rowsDropped());
    }

    @Test
    void textLinesWithoutAnAmountAreNotUnread() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "KEPT", "10.00", null, "90.00"),
                ln(0, 120, "Thank", 50, "you", 90, "for", 120));
        assertEquals(0, ProfiledReader.read(lines, bankProfile()).unreadAmountLines());
    }

    // ---- continuation stops at prose ----

    private static Line proseLine(int page, double top) {
        List<Object> spec = new ArrayList<>();
        spec.add("07/01/2026");
        spec.add(50.0);
        for (int i = 0; i < 17; i++) {
            spec.add("term" + i);
            spec.add(110.0 + i * 20);
        }
        spec.add("9.00");
        spec.add(DEBIT);
        return ln(page, top, spec.toArray());
    }

    @Test
    void aTermsPageAfterTheTableCreatesNoRowsEvenWithoutAnEndMarker() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "REAL", "10.00", null, "90.00"),
                proseLine(1, 50), proseLine(1, 60), proseLine(1, 70));
        var res = ProfiledReader.read(lines, bankProfile());
        assertEquals(1, res.outcome().results().size());
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aTermsPageEndsTheTableForGoodEvenIfADatedPageFollowsIt() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "REAL", "10.00", null, "90.00"),
                proseLine(1, 50), proseLine(1, 60),
                bankRow(2, 50, 0, "08/01/2026", "AFTER TERMS", "5.00", null, "85.00"));
        assertEquals(1, rows(lines, bankProfile()).size());
    }

    @Test
    void aTrailingSummaryPageWithoutTheHeaderStillGivesItsSummaryValues() {
        LayoutProfile p = bank("sum").summary("credit_limit", "credit limit").rightOnLine().done().build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "REAL", "10.00", null, "90.00"),
                ln(1, 50, "Credit", 40, "Limit", 80, "7,000.00", 140));
        var res = ProfiledReader.read(lines, p);
        assertEquals(7000.00, (Double) res.outcome().summaryFields().get("credit_limit"), 0.001);
        assertEquals(1, res.outcome().results().size());
    }
}
