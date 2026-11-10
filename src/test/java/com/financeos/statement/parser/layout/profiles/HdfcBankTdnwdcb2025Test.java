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
 * The HDFC savings layout of 2025 onwards on a synthetic statement: coordinates as printed, every value invented.
 * Opening 10,000.00; debit 2,000.00 -> 8,000.00; credit 5,000.00 -> 13,000.00; debit 1,500.50 -> 11,499.50.
 */
class HdfcBankTdnwdcb2025Test {

    private static final LayoutProfile PROFILE = HdfcBankTdnwdcb2025.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static final Line TABLE_HEADER = ln(1, 314, "Txn", 55, "Date", 72, "Narration", 176, "Withdrawals", 296,
            "Deposits", 386, "Closing", 473, "Balance", 505);

    /** The statement; the table header on page 1 and again on page 2 (as printed), rows with wrapped narration. */
    private static List<Line> statement(boolean bankName) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 223, "Statement", 28, "as", 80, "on", 92, ":", 106, "30/04/2026", 112));
        if (bankName) {
            l.add(ln(1, 134, "Joint", 38, "Holders", 80, ":", 130, "Not", 140, "Hdfc", 306, "Bank", 335, "Ltd", 360));
        }
        l.add(ln(1, 117, "Account", 38, "Number", 80, ":", 109, "50100012345678", 114));
        l.add(ln(1, 213, "RTGS/NEFT", 306, "IFSC", 360, ":", 380, "HDFC0001234", 390));
        l.add(ln(1, 183, "Statement", 38, "From", 88, ":", 112, "01/04/2026", 118, "To", 175, ":", 190, "30/04/2026", 198));
        l.add(TABLE_HEADER);
        // row 1: a debit, narration continues on two lines (the second one carries its own value date)
        l.add(ln(1, 328, "01/04/2026", 52, "UPI-SHOPONE-REF1", 111, "2,000.00", 338, "0.00", 428, "8,000.00", 519));
        l.add(ln(1, 337, "IFSC0001234-UPI", 111));
        l.add(ln(1, 346, "Value", 111, "Dt", 140, "01/04/2026", 150, "Ref", 210, "123456789012", 225));
        // row 2: a credit, the withdrawal column prints 0.00
        l.add(ln(1, 358, "05/04/2026", 52, "NEFT-EMPLOYER", 111, "0.00", 342, "5,000.00", 428, "13,000.00", 513));
        l.add(ln(1, 367, "SALARY", 111, "Value", 150, "Dt", 180, "05/04/2026", 195));
        // a new page repeats the header
        l.add(ln(2, 314, "Txn", 55, "Date", 72, "Narration", 176, "Withdrawals", 296, "Deposits", 386, "Closing", 473,
                "Balance", 505));
        l.add(ln(2, 328, "10/04/2026", 52, "UPI-SHOPTWO", 111, "1,500.50", 333, "0.00", 428, "11,499.50", 519));
        l.add(ln(2, 337, "Ref", 111, "123456789013", 130));
        l.add(ln(2, 364, "STATEMENT", 42, "SUMMARY", 100, ":", 150));
        l.add(ln(2, 387, "Opening", 127, "Balance", 162, "Debit", 227, "Amount", 249, "Credit", 319, "Amount", 344,
                "Closing", 448, "Balance", 480));
        l.add(ln(2, 395, "10,000.00", 133, "3,500.50", 243, "5,000.00", 338, "11,499.50", 446));
        return l;
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    @Test
    void theProfileMatchesTheHdfcSavingsLayoutWhateverIssuerWasDetected() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(true), "hdfc").id());
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(true), null).id());
        assertEquals("hdfc-bank-tdnwdcb-2025", PROFILE.id());
        assertEquals(LayoutProfile.Product.BANK, PROFILE.product());
    }

    @Test
    void theSameTableWithoutTheBanksNameIsNotThisLayout() {
        assertNull(LayoutRegistry.defaults().match(statement(false), "hdfc"));
    }

    @Test
    void theEarlierHdfcSavingsHeaderWithValueDateAndChequeColumnsIsAnotherLayout() {
        List<Line> lines = statement(true);
        lines.set(lines.indexOf(TABLE_HEADER), ln(1, 314, "Date", 0, "Narration", 100, "Chq./Ref.No.", 200, "Value", 300,
                "Dt", 345, "Withdrawal", 400, "Amt.", 445, "Deposit", 500, "Amt.", 545, "Closing", 600, "Balance", 645));
        lines.removeIf(x -> x.page() == 2 && x.top() == 314);
        assertNull(LayoutRegistry.defaults().match(lines, "hdfc"));
    }

    @Test
    void debitsAndCreditsAreSignedByTheirColumnAndTheZeroInTheOtherColumnIsNoAmount() {
        List<RowResult> r = read(statement(true)).results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2026, 4, 1), r.get(0).date);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 4, 5), r.get(1).date);
        assertEquals(5000.00, r.get(1).amount, 0.001);
        assertEquals(LocalDate.of(2026, 4, 10), r.get(2).date);
        assertEquals(-1500.50, r.get(2).amount, 0.001);
        assertTrue(r.stream().allMatch(x -> "column".equals(x.signSource)));
    }

    @Test
    void theClosingBalanceOfEveryRowChainsFromTheOpeningBalance() {
        ReadOutcome o = read(statement(true));
        assertEquals("balance-chain", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(8000.00, o.results().get(0).balance, 0.001);
        assertEquals(13000.00, o.results().get(1).balance, 0.001);
        assertEquals(11499.50, o.results().get(2).balance, 0.001);
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void theNarrationBelowARowJoinsItIncludingTheValueDateLineAndStopsAtTheNextRow() {
        List<RowResult> r = read(statement(true)).results();
        assertEquals("UPI-SHOPONE-REF1 IFSC0001234-UPI Value Dt 01/04/2026 Ref 123456789012", r.get(0).description);
        assertEquals("NEFT-EMPLOYER SALARY Value Dt 05/04/2026", r.get(1).description);
    }

    @Test
    void aValueDateInsideTheNarrationIsNotMistakenForARowDate() {
        assertEquals(3, read(statement(true)).results().size());
    }

    @Test
    void aRowOnTheNextPageUnderTheRepeatedHeaderIsRead() {
        RowResult last = read(statement(true)).results().get(2);
        assertEquals("UPI-SHOPTWO Ref 123456789013", last.description);
    }

    @Test
    void theStatementSummaryAfterTheTableIsNeverReadAsNarration() {
        ReadOutcome o = read(statement(true));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("Opening") || x.description.contains("SUMMARY")));
    }

    @Test
    void aRowWithBothFiguresZeroIsKeptAsAZeroRow() {
        List<Line> lines = statement(true);
        lines.set(lines.indexOf(lines.stream().filter(x -> x.top() == 328 && x.page() == 2).findFirst().orElseThrow()),
                ln(2, 328, "10/04/2026", 52, "UPI-SHOPTWO", 111, "0.00", 342, "0.00", 428, "13,000.00", 519));
        ReadOutcome o = read(lines);
        assertEquals(3, o.results().size());
        assertEquals(0.0, o.results().get(2).amount, 0.0);
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void periodStatementDateOpeningAndClosingBalanceAndBankAreFilled() {
        ReadOutcome o = read(statement(true));
        assertEquals(LocalDate.of(2026, 4, 1), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 4, 30), o.meta().periodEnd);
        assertEquals(LocalDate.of(2026, 4, 30), o.meta().documentDate);
        assertEquals(10000.00, o.meta().openingBalance, 0.001);
        assertEquals(11499.50, o.meta().closingBalance, 0.001);
        assertEquals("HDFC Bank", o.meta().bank);
        assertFalse(o.isCreditCard());
    }

    @Test
    void accountNumberIfscAndEveryRowsCardLast4AreFilled() {
        ReadOutcome o = read(statement(true));
        assertEquals("50100012345678", o.meta().accountNumber);
        assertEquals("HDFC0001234", o.meta().ifsc);
        assertTrue(o.results().stream().allMatch(r -> "5678".equals(r.cardLast4)));
    }

    @Test
    void theOpeningBalanceIsAlsoReportedAsPreviousBalanceLikeTheGenericReaderDoes() {
        assertEquals(10000.00, (Double) read(statement(true)).summaryFields().get("previous_balance"), 0.001);
    }

    @Test
    void aPageShiftedRightStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement(true)) {
            if (l.page() == 1 && l.top() >= 314 && l.top() < 380) {
                List<Word> ws = new ArrayList<>();
                for (Word w : l.words()) {
                    ws.add(new Word(w.text(), w.x0() + 9, w.x1() + 9, w.top(), w.page()));
                }
                shifted.add(new Line(ws));
            } else {
                shifted.add(l);
            }
        }
        List<RowResult> r = read(shifted).results();
        assertEquals(3, r.size());
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(5000.00, r.get(1).amount, 0.001);
    }
}
