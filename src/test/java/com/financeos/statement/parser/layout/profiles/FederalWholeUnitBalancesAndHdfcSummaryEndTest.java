package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two readings on synthetic text with invented values: the Federal savings banner prints the opening and closing balance
 * in whole rupees (the exact figures come from the rows' running balance), and the HDFC savings summary block under the
 * table is headed by a line of just "SUMMARY" (its figures are no unread rows).
 */
class FederalWholeUnitBalancesAndHdfcSummaryEndTest {

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static List<Line> federal(String opening, String closing, String amount, String balance) {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 32, "Account", 56, "Statement", 99, "1", 162, "June", 169, "2023", 192, "to", 219, "30", 234, "June", 246, "2023", 276));
        l.add(ln(1, 103, "SAVINGS", 407, "A/C", 440, "NO", 456, "IFSC", 500));
        l.add(ln(1, 115, "10000000000001", 407, "FDRL0001234", 500));
        l.add(ln(1, 195, "Opening", 56, "Balance", 95, "Closing", 492, "Balance", 528));
        l.add(ln(1, 225, opening, 56, closing, 492));
        l.add(ln(1, 284, "Date", 56, "Transaction", 108, "Details", 169, "Amount", 427, "Balance", 520));
        l.add(ln(1, 294, "Day/Night", 56, "Comment", 108));
        l.add(ln(1, 322, "24", 56, "Jun", 69));
        l.add(ln(1, 344, "INTEREST", 108));
        l.add(ln(1, 351, amount, 423, balance, 513));
        return l;
    }

    @Test
    void aWholeRupeeBannerGivesTheExactOpeningFromTheRowsBalance() {
        ReadOutcome o = ProfiledReader.read(federal("\u20b92,317.00", "\u20b92,340.00", "23.00", "2,340.35"),
                FederalBankDtdab2021.build()).outcome();
        assertEquals(1, o.results().size());
        assertEquals(23.00, o.results().get(0).amount, 0.001);
        assertEquals(2340.35, o.results().get(0).balance, 0.001);
        assertTrue(o.results().get(0).chainValid);
        assertEquals(2317.35, o.meta().openingBalance, 0.001);
        assertEquals("balance-chain", o.mode());
    }

    @Test
    void aSingleDebitEmptyingTheAccountIsReadAsADebit() {
        ReadOutcome o = ProfiledReader.read(federal("\u20b92,340.00", "\u20b90.00", "2,340.35", "0.00"),
                FederalBankDtdab2021.build()).outcome();
        assertEquals(-2340.35, o.results().get(0).amount, 0.001);
        assertEquals(2340.35, o.meta().openingBalance, 0.001);
    }

    private static List<Line> hdfc(String summaryHeading) {
        List<Line> l = new ArrayList<>();
        l.add(ln(1, 117, "Account", 38, "Number", 80, ":", 109, "50100012345678", 114));
        l.add(ln(1, 183, "Statement", 38, "From", 88, ":", 112, "01/04/2026", 118, "To", 175, ":", 190, "30/04/2026", 198));
        l.add(ln(1, 314, "Txn", 55, "Date", 72, "Narration", 176, "Withdrawals", 296, "Deposits", 386, "Closing", 473,
                "Balance", 505));
        l.add(ln(1, 328, "10/04/2026", 52, "UPI-SHOP", 111, "1,500.50", 333, "0.00", 428, "8,499.50", 519));
        l.add(ln(1, 364, summaryHeading, 42));
        l.add(ln(1, 387, "Opening", 127, "Balance", 162, "Debit", 227, "Amount", 249, "Credit", 319, "Amount", 344,
                "Closing", 448, "Balance", 480));
        l.add(ln(1, 395, "10,000.00", 133, "1,500.50", 243, "0.00", 338, "8,499.50", 446));
        l.add(ln(1, 420, "Total", 60, "Withdrawal", 90, "Balance***", 140, ":", 200, "8,499.50", 220));
        return l;
    }

    @Test
    void aBareSummaryHeadingEndsTheTableSoItsFiguresAreNotUnreadRows() {
        LayoutProfile p = HdfcBankTdnwdcb2025.build();
        var res = ProfiledReader.read(hdfc("SUMMARY"), p);
        assertEquals(1, res.outcome().results().size());
        assertEquals(0, res.unreadAmountLines());
        assertEquals(0, res.outcome().rowsDropped());
        assertEquals(10000.00, res.outcome().meta().openingBalance, 0.001);
    }
}
