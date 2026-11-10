package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutRegistry;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HDFC card layout of 2024 onwards (date only, no time or reward-points column), on a synthetic statement: coordinates
 * as printed, every value invented.
 */
class HdfcCardDtda2024Test {

    private static final LayoutProfile PROFILE = HdfcCardDtda2024.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static Line row(double top, String date, String narration, String amount, boolean credit) {
        List<Object> w = new ArrayList<>(List.of(date, 38));
        double x = 112;
        for (String t : narration.split(" ")) {
            w.add(t);
            w.add(x);
            x += 5.2 * t.length() + 5;
        }
        w.add(amount);
        w.add(545 - 5.2 * amount.length());
        if (credit) {
            w.add("Cr");
            w.add(548);
        }
        return ln(0, top, w.toArray());
    }

    private static List<Line> statement(boolean gstNote, String headerExtra) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 40, "HDFC", 212, "Bank", 240, "Credit", 265, "Cards", 295));
        l.add(ln(0, 70, "Card", 360, "No:", 388, "4000", 402, "12XX", 421, "XXXX", 442, "3456", 464));
        l.add(ln(0, 83, "Statement", 253, "Date:17/02/2026", 294));
        l.add(ln(0, 102, "Payment", 267, "Due", 304, "Date", 322, "Total", 396, "Dues", 418, "Minimum", 488, "Amount", 526, "Due", 558));
        l.add(ln(0, 125, "In", 17, "case", 27, "08/03/2026", 285, "3,000.00", 401, "150.00", 517));
        l.add(ln(0, 195, "Account", 379, "Summary", 417));
        l.add(ln(0, 207, "Opening", 265, "Payment/", 330, "Purchase/", 396, "Finance", 469));
        l.add(ln(0, 217, "Balance", 265, "Credits", 335, "Debits", 405, "Charges", 469));
        l.add(ln(0, 237, "1,000.00", 272, "1,000.00", 340, "3,000.00", 401, "0.00", 478, "3,000.00", 537));
        if (gstNote) {
            l.add(ln(0, 300, "How", 20, "to", 40, "read", 55, "GST", 80, "entry", 100));
        }
        l.add(ln(0, 334, "IMPORTANT", 254, "INFORMATION", 303));
        l.add(headerExtra.isEmpty()
                ? ln(0, 444, "Date", 48, "Transaction", 110, "Description", 164, "Amount", 504, "(in", 540, "Rs.)", 554)
                : ln(0, 444, "Date", 48, headerExtra, 70, "Transaction", 110, "Description", 164, "Amount", 504, "(in", 540, "Rs.)", 554));
        l.add(row(483, "21/01/2026", "UPI TESTSHOP CITY", "2,000.00", false));
        l.add(row(494, "22/01/2026", "NEFT PAYMENT RECEIVED (Ref# 000000000000000001)", "1,000.00", true));
        l.add(row(505, "23/01/2026", "MERCHANT TWO", "1,000.00", false));
        return l;
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    @Test
    void theProfileMatchesThisLayoutForHdfc() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(true, ""), "hdfc").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuerOrAStatementWithoutTheGstNote() {
        assertNull(LayoutRegistry.defaults().match(statement(true, ""), "axis"));
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(statement(false, ""), "hdfc"));
    }

    @Test
    void aHeaderWithATimeColumnIsAnotherLayout() {
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(statement(true, "&TIME"), "hdfc"));
    }

    @Test
    void rowsAreReadWithSignsFromTheCrMarker() {
        List<RowResult> r = read(statement(true, "")).results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2026, 1, 21), r.get(0).date);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(1000.00, r.get(1).amount, 0.001);
        assertEquals("NEFT PAYMENT RECEIVED (Ref# 000000000000000001)", r.get(1).description);
        assertEquals(-1000.00, r.get(2).amount, 0.001);
        assertTrue(r.stream().allMatch(x -> "3456".equals(x.cardLast4)));
    }

    @Test
    void summaryValuesAndMetaAreRead() {
        ReadOutcome o = read(statement(true, ""));
        assertEquals(LocalDate.of(2026, 2, 17), o.meta().periodEnd);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(150.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(0.00, (Double) o.summaryFields().get("finance_charges"), 0.001);
    }
}
