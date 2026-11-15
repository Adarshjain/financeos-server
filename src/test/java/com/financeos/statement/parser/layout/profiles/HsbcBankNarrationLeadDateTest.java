package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** HSBC account table, synthetic invented values: a narration that starts with its own date is the row's date. */
class HsbcBankNarrationLeadDateTest {

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static List<Line> base() {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 103, "HSBC", 311, "Statement", 430));
        l.add(ln(2, 624, "Date", 36, "Transaction", 102, "Details", 147, "Deposits", 335, "Withdrawals", 408, "Balance", 522));
        return l;
    }

    private static List<RowResult> read(List<Line> l) {
        List<RowResult> r = new ArrayList<>(ProfiledReader.read(l, HsbcBankDtddwb2026.build()).outcome().results());
        r.sort((a, b) -> a.date.compareTo(b.date));
        return r;
    }

    @Test
    void aLeadingNarrationDateIsTheRowDateAndIsNotNarration() {
        List<Line> l = base();
        l.add(ln(2, 657, "05Jul2026", 36, "03JUL26", 102, "IMPS", 140, "PAYIN", 170));
        l.add(ln(2, 667, "REF123", 102, "300.00", 343, "1,300.00", 502));
        l.add(ln(2, 690, "02JUL26", 102, "UPI", 140, "SHOPX", 165));
        l.add(ln(2, 700, "50.00", 410, "1,250.00", 502));
        List<RowResult> r = read(l);
        assertEquals(2, r.size());
        assertEquals(LocalDate.of(2026, 7, 2), r.get(0).date);
        assertEquals("UPI SHOPX", r.get(0).description);
        assertEquals(LocalDate.of(2026, 7, 3), r.get(1).date);
        assertEquals("IMPS PAYIN REF123", r.get(1).description);
    }

    @Test
    void aRowWithoutALeadingDateKeepsThePrintedDate() {
        List<Line> l = base();
        l.add(ln(2, 657, "05Jul2026", 36, "IMPS", 102, "PAYIN", 130));
        l.add(ln(2, 667, "300.00", 343, "1,300.00", 502));
        l.add(ln(2, 690, "UPI", 102, "SHOPX", 130));
        l.add(ln(2, 700, "50.00", 410, "1,250.00", 502));
        List<RowResult> r = read(l);
        assertEquals(2, r.size());
        assertEquals(LocalDate.of(2026, 7, 5), r.get(0).date);
        assertEquals(LocalDate.of(2026, 7, 5), r.get(1).date);
    }

    @Test
    void aLeadingDateAfterTheBlockDateOrFarBeforeItIsNotTaken() {
        List<Line> l = base();
        l.add(ln(2, 657, "05Jul2026", 36, "09JUL26", 102, "IMPS", 140));
        l.add(ln(2, 667, "300.00", 343, "1,300.00", 502));
        l.add(ln(2, 690, "01JUN26", 102, "UPI", 140));
        l.add(ln(2, 700, "50.00", 410, "1,250.00", 502));
        List<RowResult> r = read(l);
        assertEquals(2, r.size());
        assertEquals(LocalDate.of(2026, 7, 5), r.get(0).date);
        assertEquals(LocalDate.of(2026, 7, 5), r.get(1).date);
    }
}
