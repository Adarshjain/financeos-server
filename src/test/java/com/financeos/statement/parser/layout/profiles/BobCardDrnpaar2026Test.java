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
 * The Bank of Baroda card layout of 2026 on a synthetic statement: coordinates as printed, every value invented.
 * Opening 1,000 + purchases 2,300 - payments 500 = closing 2,800.
 */
class BobCardDrnpaar2026Test {

    private static final LayoutProfile PROFILE = BobCardDrnpaar2026.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static List<Line> statement() {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 66, "Statement", 214, "Date", 262, ":", 285, "07/03/2026", 291, "|", 359, "Statement", 370,
                "Period", 419, ":", 448, "8", 454, "Feb,", 462, "2026", 484, "to", 508, "7", 520, "Mar,", 528,
                "2026", 550));
        l.add(ln(0, 115, "Card", 208, "No:", 231, "123456XXXXXX7777", 249, "Sanctioned", 422, "Credit", 473,
                "Limit:", 502, "₹200,000", 529));
        l.add(ln(0, 145, "Card", 20, ":", 41, "VISA", 45, "Payment", 222, "Due", 263, "Date", 283, "Minimum", 341,
                "Amount", 383, "Due", 420, "Total", 476, "Amount", 500, "Due", 537));
        l.add(ln(0, 167, "State", 20, ":", 44, "KERALA", 48, "07/04/2026", 236));
        l.add(ln(0, 181, "250.00", 372, "₹", 479, "2,800.00DR", 488));
        l.add(ln(0, 222, "Credit", 224, "Limit", 251, "Available", 299, "Credit", 338, "Limit", 365, "Cash", 416,
                "Limit", 438, "Available", 490, "Cash", 529, "Limit", 551));
        l.add(ln(0, 240, "₹", 228, "200,000", 235, "₹", 316, "197,200.00", 323, "₹", 413, "40,000.00", 420,
                "₹", 507, "40,000.00", 515));
        l.add(ln(0, 294, "New", 426, "Purchases", 450));
        l.add(ln(0, 300, "Opening", 212, "Balance", 251, "Payment/Credits", 307, "Closing", 495, "Balance", 529));
        l.add(ln(0, 305, "Purchases/Debits", 399));
        l.add(ln(0, 323, "₹", 218, "1,000.00", 227, "₹", 313, "500.00", 322, "₹", 408, "2,300.00", 417,
                "₹", 502, "2,800.00", 511));
        l.add(ln(0, 392, "Bonus/Reward", 215, "Points", 294, "Summary", 331));
        l.add(ln(0, 420, "Opening", 213, "Balance", 252, "Earned", 328, "Closing", 495, "Balance", 529));
        l.add(ln(0, 477, "Transaction", 14, "Details", 80));
        l.add(ln(0, 516, "Date", 27, "Ref.", 72, "No.", 93, "Particulars", 204, "Source", 385, "Source", 434, "Amt.", 469,
                "Amount", 525));
        l.add(ln(0, 519, "Reward", 344));
        l.add(ln(0, 524, "Currency", 379));
        l.add(ln(0, 530, "Points", 347));
        l.add(ln(0, 541, "TEST", 120, "HOLDER", 150, "(PRIMARY", 183, "CARD", 225, "-", 250, "7777)", 256));
        l.add(ln(0, 554, "01/03/2026", 20, "123456", 78, "TESTSHOP", 120, "ONE", 175, "Kochi", 200, "IN", 240, "3", 361,
                "INR", 394, "500.00", 474, "500.00", 542, "DR", 569));
        l.add(ln(0, 567, "02/03/2026", 20, "123456", 78, "TESTSHOP", 120, "TWO", 175, "NY", 200, "US", 220, "-14", 357,
                "USD", 393, "10.00", 478, "800.00", 542, "DR", 569));
        l.add(ln(0, 580, "03/03/2026", 20, "BBPS-PAYMENT", 120, "INR", 394, "500.00", 462, "500.00", 531, "CR", 570));
        l.add(ln(0, 602, "ADD-ON", 120, "HOLDER", 160, "(ADD-ON", 200, "CARD", 240, "-", 265, "8888)", 272));
        l.add(ln(0, 615, "04/03/2026", 20, "123456", 78, "TESTSHOP", 120, "THREE", 175, "Pune", 220, "IN", 255, "INR", 394,
                "1,000.00", 467, "1,000.00", 535, "DR", 569));
        l.add(ln(0, 628, "05/03/2026", 20, "SPEND", 120, "BASED", 150, "REWARD", 180, "25", 351));
        l.add(ln(0, 800, "Page", 274, "1", 297));
        return l;
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    private static List<Line> without(List<Line> lines, double top) {
        List<Line> out = new ArrayList<>(lines);
        out.removeIf(x -> x.top() == top);
        return out;
    }

    @Test
    void theProfileMatchesTheBobLayoutOfThisStatement() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(), "bank_of_baroda").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        LayoutProfile hdfc = LayoutRegistry.defaults().match(statement(), "hdfc");
        LayoutProfile yes = LayoutRegistry.defaults().match(statement(), "yes");
        assertTrue(hdfc == null || !PROFILE.id().equals(hdfc.id()));
        assertTrue(yes == null || !PROFILE.id().equals(yes.id()));
    }

    @Test
    void aBobStatementWithoutTheBonusRewardBlockIsAnotherLayout() {
        List<Line> lines = without(without(statement(), 392), 420);
        assertNull(LayoutRegistry.defaults().match(lines, "bank_of_baroda"));
    }

    @Test
    void rowsAreReadWithDatesAndSignsFromTheCrDrMarker() {
        List<RowResult> r = read(statement()).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 3, 1), r.get(0).date);
        assertEquals(-500.00, r.get(0).amount, 0.001);
        assertEquals(-800.00, r.get(1).amount, 0.001);
        assertEquals(500.00, r.get(2).amount, 0.001);
        assertEquals(-1000.00, r.get(3).amount, 0.001);
        assertTrue(r.stream().allMatch(x -> "suffix".equals(x.signSource)));
    }

    @Test
    void theForeignAmountColumnIsIgnoredAndTheRupeeAmountIsRead() {
        RowResult foreign = read(statement()).results().get(1);
        assertEquals(-800.00, foreign.amount, 0.001);
    }

    @Test
    void theRewardPointsAreNoNarrationButAForeignCurrencyCodeIs() {
        List<RowResult> r = read(statement()).results();
        assertEquals("TESTSHOP ONE Kochi IN", r.get(0).description);
        assertEquals("TESTSHOP TWO NY US USD", r.get(1).description);
        assertEquals("BBPS-PAYMENT", r.get(2).description);
    }

    @Test
    void theAddOnHolderLineAboveARowJoinsItButThePrimaryHolderLineDoesNot() {
        List<RowResult> r = read(statement()).results();
        assertEquals("ADD-ON HOLDER (ADD-ON CARD - 8888) TESTSHOP THREE Pune IN", r.get(3).description);
        assertFalse(r.get(0).description.contains("PRIMARY"));
    }

    @Test
    void theDatedSpendBasedRewardLineIsNeitherARowNorADroppedLine() {
        ReadOutcome o = read(statement());
        assertEquals(4, o.results().size());
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void everyRowCarriesTheLast4OfTheCardNumber() {
        assertTrue(read(statement()).results().stream().allMatch(x -> "7777".equals(x.cardLast4)));
    }

    @Test
    void periodStatementDateAccountNumberAndLimitAreFilled() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 2, 8), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 3, 7), o.meta().periodEnd);
        assertEquals(LocalDate.of(2026, 3, 7), o.meta().documentDate);
        assertEquals("123456XXXXXX7777", o.meta().accountNumber);
        assertTrue(o.isCreditCard());
        assertEquals(200000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void theDuesRowGivesDueDateMinimumAndTotal() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 4, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(250.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(2800.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void theLimitsRowGivesAvailableCreditAndCashLimits() {
        ReadOutcome o = read(statement());
        assertEquals(197200.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(40000.00, (Double) o.summaryFields().get("cash_limit"), 0.001);
        assertEquals(40000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theAccountSummaryRowGivesOpeningPaymentsPurchasesAndClosing() {
        ReadOutcome o = read(statement());
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(500.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(2300.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(1000.00, o.meta().openingBalance.doubleValue(), 0.001);
        assertEquals(2800.00, o.meta().closingBalance.doubleValue(), 0.001);
    }

    @Test
    void aPageShiftedRightStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement()) {
            if (l.top() >= 516 && l.top() < 800) {
                List<Word> ws = new ArrayList<>();
                for (Word w : l.words()) {
                    ws.add(new Word(w.text(), w.x0() + 3, w.x1() + 3, w.top(), w.page()));
                }
                shifted.add(new Line(ws));
            } else {
                shifted.add(l);
            }
        }
        List<RowResult> r = read(shifted).results();
        assertEquals(4, r.size());
        assertEquals(-500.00, r.get(0).amount, 0.001);
        assertEquals(500.00, r.get(2).amount, 0.001);
    }

    @Test
    void theProfileDefinitionIsTheBobCardOfTheCardProduct() {
        assertEquals("bank_of_baroda-card-drnpaar-2026", PROFILE.id());
        assertEquals("bank_of_baroda", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
