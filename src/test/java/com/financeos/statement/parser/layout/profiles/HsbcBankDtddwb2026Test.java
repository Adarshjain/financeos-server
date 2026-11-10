package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutRegistry;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HSBC composite statement's account table of 2026 on synthetic text, coordinates as printed: the layout's identity and
 * its block rows (the date printed once per day, same-day rows without a date, a value date in the narration, a block that
 * runs over a page break, the spreadsheet export with single-line rows newest first).
 */
class HsbcBankDtddwb2026Test {

    private static final LayoutProfile PROFILE = HsbcBankDtddwb2026.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static List<Line> statement(boolean carriedForward, String bank) {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 103, bank, 311, "Statement", 430));
        l.add(ln(2, 624, "Date", 36, "Transaction", 102, "Details", 147, "Deposits", 335, "Withdrawals", 408, "Balance", 522));
        l.add(ln(2, 657, "01Jul2026", 36, "BALANCE", 102, "BROUGHT", 142, "FORWARD", 184, "1,000.00", 502));
        if (carriedForward) {
            l.add(ln(3, 735, "Balance", 103, "Carried", 134, "Forward", 162, "1,000.00", 507));
        }
        return l;
    }

    @Test
    void theProfileMatchesTheHsbcAccountTable() {
        assertEquals("hsbc-bank-dtddwb-2026", PROFILE.id());
        assertEquals(LayoutProfile.Product.BANK, PROFILE.product());
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(true, "HSBC"), "hsbc").id());
    }

    @Test
    void aTableOfAnotherBankIsNotThisLayout() {
        assertNull(LayoutRegistry.defaults().match(statement(true, "OTHER"), "hsbc"));
    }

    @Test
    void aStatementWithoutTheCarriedForwardRowIsStillThisLayout() {
        // the spreadsheet export of the statement has no brought / carried forward rows
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(false, "HSBC"), "hsbc").id());
    }

    private static Line header(int page, double top) {
        return ln(page, top, "Date", 36, "Transaction", 102, "Details", 147, "Deposits", 335, "Withdrawals", 408, "Balance", 522);
    }

    private static List<Line> blockStatement() {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 103, "HSBC", 311, "Statement", 430));
        l.add(ln(1, 174, "Statement", 317, "Date", 356, "02Jul2026", 502));
        l.add(header(2, 624));
        l.add(ln(2, 657, "01Jul2026", 36, "BALANCE", 102, "BROUGHT", 142, "FORWARD", 184, "1,000.00", 502));
        // dated row: value date and first narration on the date line, more narration, the amount line
        l.add(ln(2, 670, "01Jul2026", 36, "01JUL26", 102, "CARD", 145, "SWIPE", 170));
        l.add(ln(2, 683, "REF123", 102));
        l.add(ln(2, 696, "SHOP", 102, "100.00", 413, "900.00", 507));
        // same day, no date: a new row from its narration line
        l.add(ln(2, 709, "NEFT", 102, "IN", 130));
        l.add(ln(2, 722, "SALARY", 102, "500.00", 343, "1,400.00", 502));
        // the next day's row starts here and runs over the page break
        l.add(ln(2, 722.5, "02Jul2026", 36, "LATER", 102, "PART", 140));
        l.add(ln(2, 735, "Balance", 103, "Carried", 134, "Forward", 162, "1,400.00", 507));
        l.add(header(3, 187));
        l.add(ln(3, 207, "Balance", 103, "Brought", 134, "Forward", 164, "1,400.00", 507));
        l.add(ln(3, 217, "TAIL", 102, "50.00", 421, "1,350.00", 507));
        l.add(ln(3, 359, "CLOSING", 103, "BALANCE", 140, "1,350.00", 507));
        l.add(ln(3, 373, "Total", 103, "Total", 140, "650.00", 417, "500.00", 507));
        return l;
    }

    @Test
    void blockRowsAreReadWithTheDatePrintedOncePerDay() {
        ReadOutcome o = ProfiledReader.read(blockStatement(), PROFILE).outcome();
        List<RowResult> r = o.results();
        assertEquals(3, r.size(), r.toString());
        assertEquals(LocalDate.of(2026, 7, 1), r.get(0).date);
        assertEquals("CARD SWIPE REF123 SHOP", r.get(0).description, "the value date in the narration is not narration");
        assertEquals(-100.0, r.get(0).amount, 0.001);
        assertEquals(900.0, r.get(0).balance, 0.001);
        assertEquals(LocalDate.of(2026, 7, 1), r.get(1).date);
        assertEquals("NEFT IN SALARY", r.get(1).description);
        assertEquals(500.0, r.get(1).amount, 0.001);
        assertEquals("balance-chain", o.mode());
    }

    @Test
    void aBlockRunsOverThePageBreakAndTheClosingBalanceEndsTheTable() {
        List<RowResult> r = ProfiledReader.read(blockStatement(), PROFILE).outcome().results();
        assertEquals(LocalDate.of(2026, 7, 2), r.get(2).date);
        assertEquals("LATER PART TAIL", r.get(2).description);
        assertEquals(-50.0, r.get(2).amount, 0.001);
        assertEquals(3, r.size(), "the closing balance and totals lines are not rows");
    }

    @Test
    void theSpreadsheetExportReadsSingleLineRowsNewestFirst() {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 0, "HSBC", 100, "ACCOUNT-", 200, "001-234567-006", 245));
        l.add(ln(0, 10, "Date", 100, "Transaction", 200, "Details", 245, "Deposits", 300, "Withdrawals", 400, "Balance", 500));
        l.add(ln(0, 20, "05Mar2026", 100, "SHOP", 200, "TWO", 230, "10.00", 400, "80.00", 500));
        l.add(ln(0, 30, "04Mar2026", 100, "SHOP", 200, "ONE", 230, "20.00", 400, "90.00", 500));
        l.add(ln(0, 40, "03Mar2026", 100, "PAY", 200, "IN", 230, "110.00", 300, "110.00", 500));
        ReadOutcome o = ProfiledReader.read(l, PROFILE).outcome();
        List<RowResult> r = o.results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2026, 3, 3), r.get(0).date, "oldest first after ordering");
        assertEquals(110.0, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 3, 5), r.get(2).date);
        assertEquals("001-234567-006", o.meta().accountNumber);
        assertEquals("3006", r.get(0).cardLast4);
    }
}
