package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two readings checked against the statements' own text, synthetic invented values: the second header line of the
 * Bank of Baroda card table ("Points Currency", the header wrapped over two lines) is no narration of the first row, and
 * the HSBC account table's printed "OPENING BALANCE" row of a first statement is read as opening and previous balance.
 */
class BobAndHsbcBankLayoutFixesTest {

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    @Test
    void theWrappedSecondHeaderLineIsNotTheNarrationOfTheFirstRow() {
        List<Line> l = new ArrayList<>();
        // a continuation page: the header repeats and its second line sits where the narration of the first row ends
        l.add(ln(0, 56, "Date", 29, "Ref.", 75, "No.", 96, "Particulars", 209, "Reward", 348, "Source", 387,
                "Source", 437, "Amt.", 468, "Amount", 528));
        l.add(ln(0, 64, "Points", 351, "Currency", 383));
        l.add(ln(0, 76, "01/03/2026", 20, "123456", 78, "TESTSHOP", 120, "ONE", 175, "Kochi", 200, "IN", 240, "3", 361,
                "INR", 394, "500.00", 474, "500.00", 542, "DR", 569));
        List<RowResult> r = ProfiledReader.read(l, BobCardDrnpaar2026.build()).outcome().results();
        assertEquals(1, r.size());
        assertEquals("TESTSHOP ONE Kochi IN", r.get(0).description);
    }

    @Test
    void thePrintedOpeningBalanceRowIsTheOpeningAndPreviousBalance() {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 103, "HSBC", 311, "Statement", 430));
        l.add(ln(2, 624, "Date", 36, "Transaction", 102, "Details", 147, "Deposits", 335, "Withdrawals", 408, "Balance", 522));
        l.add(ln(2, 657, "OPENING", 102, "BALANCE", 142, "0.00", 502));
        l.add(ln(2, 670, "01Jul2026", 36, "IMPS", 102, "PAYIN", 130, "300.00", 343, "300.00", 502));
        l.add(ln(2, 735, "CLOSING", 103, "BALANCE", 140, "300.00", 507));
        ReadOutcome o = ProfiledReader.read(l, HsbcBankDtddwb2026.build()).outcome();
        assertEquals(1, o.results().size());
        assertEquals(0.0, o.meta().openingBalance, 0.001);
        assertEquals(0.0, ((Number) o.summaryFields().get("previous_balance")).doubleValue(), 0.001);
    }

    @Test
    void aStatementWithoutAnOpeningBalanceRowHasNoPreviousBalance() {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 103, "HSBC", 311, "Statement", 430));
        l.add(ln(2, 624, "Date", 36, "Transaction", 102, "Details", 147, "Deposits", 335, "Withdrawals", 408, "Balance", 522));
        l.add(ln(2, 657, "01Jul2026", 36, "BALANCE", 102, "BROUGHT", 142, "FORWARD", 184, "1,000.00", 502));
        l.add(ln(2, 670, "02Jul2026", 36, "IMPS", 102, "PAYIN", 130, "300.00", 343, "1,300.00", 502));
        ReadOutcome o = ProfiledReader.read(l, HsbcBankDtddwb2026.build()).outcome();
        assertNull(o.summaryFields().get("previous_balance"));
    }
}
