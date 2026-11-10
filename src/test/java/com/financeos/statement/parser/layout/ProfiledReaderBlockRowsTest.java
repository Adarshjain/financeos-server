package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.RowResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Block rows: a row is a date line plus the lines below it, the amounts sit on one line of the block. Invented values. */
class ProfiledReaderBlockRowsTest {

    private static LayoutProfile.Builder blocks(String id) {
        return bank(id).dateFormats("dd/MM/yyyy", "dd MMM").blockRows();
    }

    private static LayoutProfile.Builder yearless(String id) {
        return blocks(id).summary(LayoutProfile.MetaTarget.PERIOD, "statement\\s+period")
                .type(LayoutProfile.ValueType.DATE_RANGE).rightOnLine().done();
    }

    private static ProfiledReader.Result read(List<Line> lines, LayoutProfile p) {
        return ProfiledReader.read(lines, p);
    }

    private static Line amountLine(int page, double top, String debit, String balance) {
        return ln(page, top, debit, DEBIT, balance, BAL);
    }

    @Test
    void aDateOnlyLineThenNarrationThenTheAmountLineIsOneRow() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE),
                ln(0, 118, "UPI SHOP", NARR),
                ln(0, 126, "REF 1234", NARR),
                amountLine(0, 134, "10.00", "90.00"));
        ProfiledReader.Result res = read(lines, blocks("a").build());
        List<RowResult> r = res.outcome().results();
        assertEquals(1, r.size());
        assertEquals(LocalDate.of(2026, 1, 5), r.get(0).date);
        assertEquals("UPI SHOP REF 1234", r.get(0).description);
        assertEquals(-10.0, r.get(0).amount, 0.001);
        assertEquals(90.0, r.get(0).balance, 0.001);
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aDateAndFirstNarrationLineThenMoreNarrationThenTheAmountLineIsOneRow() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE, "NEFT FROM", NARR),
                ln(0, 118, "ACME CORP", NARR),
                ln(0, 126, "SALARY", NARR, "500.00", CREDIT, "590.00", BAL));
        List<RowResult> r = read(lines, blocks("b").build()).outcome().results();
        assertEquals(1, r.size());
        assertEquals("NEFT FROM ACME CORP SALARY", r.get(0).description);
        assertEquals(500.0, r.get(0).amount, 0.001);
        assertEquals(590.0, r.get(0).balance, 0.001);
    }

    @Test
    void aSingleLineRowInsideABlockTableIsReadAsBefore() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", "10.00", null, "90.00"),
                ln(0, 120, "06/01/2026", DATE),
                ln(0, 128, "SHOP TWO", NARR),
                amountLine(0, 136, "5.00", "85.00"),
                bankRow(0, 146, 0, "07/01/2026", "SHOP THREE", null, "20.00", "105.00"));
        ProfiledReader.Result res = read(lines, blocks("c").build());
        List<RowResult> r = res.outcome().results();
        assertEquals(3, r.size());
        assertEquals("SHOP ONE", r.get(0).description);
        assertEquals("SHOP TWO", r.get(1).description);
        assertEquals(-5.0, r.get(1).amount, 0.001);
        assertEquals("SHOP THREE", r.get(2).description);
        assertEquals(20.0, r.get(2).amount, 0.001);
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aBlockWithoutAnAmountLineIsUnreadNeverGuessed() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE),
                ln(0, 118, "NO AMOUNT HERE", NARR),
                bankRow(0, 130, 0, "06/01/2026", "SHOP", "5.00", null, "95.00"));
        ProfiledReader.Result res = read(lines, blocks("d").build());
        assertEquals(1, res.outcome().results().size());
        assertEquals("SHOP", res.outcome().results().get(0).description);
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void aBlockWithTwoAmountLinesIsUnreadNeverGuessed() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE),
                ln(0, 118, "TWO FIGURES", NARR),
                amountLine(0, 126, "10.00", "90.00"),
                amountLine(0, 134, "20.00", "70.00"),
                bankRow(0, 146, 0, "06/01/2026", "SHOP", "5.00", null, "65.00"));
        ProfiledReader.Result res = read(lines, blocks("e").build());
        assertEquals(1, res.outcome().results().size());
        assertEquals("SHOP", res.outcome().results().get(0).description);
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void yearlessDatesOfBlocksTakeTheirYearFromAPeriodAcrossTheYearBoundary() {
        List<Line> lines = List.of(
                ln(0, 50, "Statement", 40, "Period", 100, "20/12/2025", 150, "-", 220, "19/01/2026", 230),
                bankHeader(0, 100, 0),
                ln(0, 110, "28 Dec", DATE),
                ln(0, 118, "LATE DEC", NARR),
                amountLine(0, 126, "10.00", "90.00"),
                ln(0, 140, "03 Jan", DATE),
                ln(0, 148, "EARLY JAN", NARR),
                amountLine(0, 156, "10.00", "80.00"));
        List<RowResult> r = read(lines, yearless("f").build()).outcome().results();
        assertEquals(2, r.size());
        assertEquals(LocalDate.of(2025, 12, 28), r.get(0).date);
        assertEquals(LocalDate.of(2026, 1, 3), r.get(1).date);
    }

    @Test
    void aBlockCanRunOverAPageBreak() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE),
                ln(0, 118, "FIRST PART", NARR),
                bankHeader(1, 60, 0),
                ln(1, 70, "SECOND PART", NARR),
                amountLine(1, 78, "10.00", "90.00"));
        List<RowResult> r = read(lines, blocks("g").build()).outcome().results();
        assertEquals(1, r.size());
        assertEquals("FIRST PART SECOND PART", r.get(0).description);
    }

    // ---- blockEndsAtAmountLine ----

    @Test
    void withoutTheOptionADatelessLineAfterTheAmountLineStaysInTheBlock() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE),
                ln(0, 118, "ONE", NARR),
                amountLine(0, 126, "10.00", "90.00"),
                ln(0, 134, "TRAILING", NARR));
        List<RowResult> r = read(lines, blocks("h").build()).outcome().results();
        assertEquals(1, r.size());
        assertEquals("ONE TRAILING", r.get(0).description);
    }

    @Test
    void theBlockClosesAtItsAmountLineAndTheNextRowOfTheDayTakesTheDate() {
        LayoutProfile p = blocks("i").blockEndsAtAmountLine(0).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE, "FIRST", NARR),
                amountLine(0, 118, "10.00", "90.00"),
                ln(0, 126, "SECOND", NARR),
                ln(0, 134, "MORE", NARR, "5.00", DEBIT, "85.00", BAL),
                ln(0, 142, "06/01/2026", DATE, "NEXT DAY", NARR, "5.00", DEBIT, "80.00", BAL));
        ProfiledReader.Result res = read(lines, p);
        List<RowResult> r = res.outcome().results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2026, 1, 5), r.get(1).date);
        assertEquals("SECOND MORE", r.get(1).description);
        assertEquals(LocalDate.of(2026, 1, 6), r.get(2).date);
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void linesDirectlyUnderTheAmountLineAreItsNarrationAndLaterOnesStartTheNextRow() {
        LayoutProfile p = blocks("j").blockEndsAtAmountLine(8).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE),
                ln(0, 120, "ONE", NARR),
                amountLine(0, 130, "10.00", "90.00"),
                ln(0, 135, "TAIL", NARR),
                ln(0, 170, "TWO", NARR),
                amountLine(0, 180, "5.00", "85.00"));
        List<RowResult> r = read(lines, p).outcome().results();
        assertEquals(2, r.size());
        assertEquals("ONE TAIL", r.get(0).description);
        assertEquals("TWO", r.get(1).description);
        assertEquals(LocalDate.of(2026, 1, 5), r.get(1).date);
    }

    @Test
    void aDatelessLineWithoutNarrationDoesNotStartARow() {
        LayoutProfile p = blocks("k").blockEndsAtAmountLine(0).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE, "ONE", NARR),
                amountLine(0, 118, "10.00", "90.00"),
                ln(0, 126, "(DR=Debit)", BAL));
        ProfiledReader.Result res = read(lines, p);
        assertEquals(1, res.outcome().results().size());
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aNegativeTrailingGapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> blocks("l").blockEndsAtAmountLine(-1));
    }

    // ---- narrationDropsDates ----

    @Test
    void aValueDateInTheNarrationIsDroppedOnlyWhenTheProfileAsksFor() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", DATE, "06/01/2026", NARR, "SHOP", 180, "10.00", DEBIT, "90.00", BAL));
        assertEquals("06/01/2026 SHOP", read(lines, blocks("m").build()).outcome().results().get(0).description);
        assertEquals("SHOP", read(lines, blocks("n").narrationDropsDates().build()).outcome().results().get(0).description);
    }
}
