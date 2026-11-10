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
 * The Axis card layout of 2024 on a synthetic statement: coordinates as printed, every value invented. Previous balance
 * 1,000 Dr - payments 1,000 - credits 0 + purchases 3,000 + cash 0 + other 50 = total due 3,050 Dr.
 */
class AxisCardDtda2024Test {

    private static final LayoutProfile PROFILE = AxisCardDtda2024.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    /** The statement; headerExtra is appended to the table header line (e.g. the cashback column of another layout). */
    private static List<Line> statement(String headerExtra, boolean splitPeriodLabel) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 60, "AXIS", 296, "BANK", 330, "CREDIT", 353, "CARD", 397, "STATEMENT", 433));
        l.add(ln(0, 210, "PAYMENT", 252, "SUMMARY", 297));
        l.add(splitPeriodLabel
                ? ln(0, 224, "Total", 52, "Payment", 70, "Due", 101, "Minimum", 152, "Payment", 185, "Due", 216,
                        "Statement", 272, "Per", 308, "iod", 322, "Payment", 381, "Due", 412, "Date", 427,
                        "Statement", 472, "Generation", 509, "Date", 548)
                : ln(0, 224, "Total", 52, "Payment", 70, "Due", 101, "Minimum", 152, "Payment", 185, "Due", 216,
                        "Statement", 272, "Period", 308, "Payment", 381, "Due", 412, "Date", 427,
                        "Statement", 472, "Generation", 509, "Date", 548));
        l.add(ln(0, 237, "3,050.00", 65, "Dr", 100, "305.00", 177, "Dr", 215, "20/06/2026", 259, "-", 299,
                "18/07/2026", 303, "07/08/2026", 393, "18/07/2026", 510));
        l.add(ln(0, 253, "Credit", 51, "Card", 72, "Number", 89, "Credit", 172, "Limit", 194, "Available", 265,
                "Credit", 298, "Limit", 319, "Available", 379, "Cash", 411, "Limit", 429));
        l.add(ln(0, 266, "451457******1234", 51, "100,000.00", 173, "97,000.00", 283, "20,000.00", 396));
        l.add(ln(0, 282, "Previous", 69, "Balance", 99, "-", 126, "Payments", 130, "-", 164, "Credits", 168, "+", 193,
                "Purchase", 199, "+", 230, "Cash", 236, "Advance", 253, "+", 283, "Other", 289, "Debit&Charges", 310,
                "=Total", 361, "Payment", 383, "Due", 414));
        l.add(ln(0, 295, "1,000.00", 51, "Dr", 77, "1,000.00", 119, "0.00", 168, "3,000.00", 218, "0.00", 273,
                "50.00", 343, "3,050.00", 410, "Dr", 437));
        l.add(ln(0, 307, "Account", 271, "Summary", 306));
        l.add(ln(0, 319, "DATE", 49, "TRANSACTION", 183, "DETAILS", 233, "MERCHANT", 386, "CATEGORY", 427,
                "AMOUNT", 514, "(Rs.)", 547));
        if (!headerExtra.isEmpty()) { // another layout's wider header replaces the plain one
            l.set(l.size() - 1, ln(0, 319, "DATE", 49, "TRANSACTION", 183, "DETAILS", 233, "MERCHANT", 386,
                    "CATEGORY", 427, "AMOUNT", 514, "(Rs.)", 547, headerExtra, 600));
        }
        l.add(ln(0, 329, "Card", 83, "No:", 100, "451457******1234", 122, "Name", 260, "TEST", 287, "USER", 317));
        l.add(ln(0, 339, "21/06/2026", 35, "UPI", 90, "TESTSHOP,CITY", 112, "GROCERY", 360, "2,000.00", 548, "Dr", 575));
        l.add(ln(0, 349, "22/06/2026", 35, "BBPS", 90, "PAYMENT", 120, "RECEIVED", 160, "1,000.00", 548, "Cr", 575));
        l.add(ln(0, 359, "23/06/2026", 35, "MERCHANT", 90, "ONE", 140, "TRAVEL", 360, "1,000.00", 548, "Dr", 575));
        l.add(ln(0, 369, "24/06/2026", 35, "ANNUAL", 90, "FEE", 125, "50.00", 560, "Dr", 575));
        l.add(ln(0, 379, "****", 253, "End", 273, "of", 288, "Statement", 297, "****", 337));
        l.add(ln(0, 390, "25/06/2026", 35, "AFTER", 90, "THE", 120, "END", 140, "9,999.00", 548, "Dr", 575));
        l.add(ln(0, 754, "eDGE", 43, "REWARD", 63, "BALANCE", 111, "AS", 143, "CUSTOMER", 164, "ID", 203));
        l.add(ln(0, 762, "POINTS", 56, "ON", 117, "DATE", 129));
        l.add(ln(0, 777, "1,250", 60, "30-06-2026", 114, "123456789", 169));
        l.add(ln(1, 60, "AXIS", 296, "BANK", 330, "CREDIT", 353, "CARD", 397, "STATEMENT", 433));
        l.add(ln(1, 90, "26/06/2026", 35, "TERMS", 90, "TEXT", 130, "8,888.00", 548, "Dr", 575));
        return l;
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    @Test
    void theProfileMatchesTheAxisLayoutOfThisStatement() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement("", false), "axis").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        assertNull(LayoutRegistry.defaults().match(statement("", false), "hdfc"));
    }

    @Test
    void theCashbackColumnVariantIsAnotherLayout() {
        assertNull(LayoutRegistry.defaults().match(statement("CASHBACK", false), "axis"));
    }

    @Test
    void theSplitWordGenerationOfTheSameTableIsNotThisLayout() {
        assertNull(LayoutRegistry.defaults().match(statement("", true), "axis"));
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromTheCrDrMarker() {
        List<RowResult> r = read(statement("", false)).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 6, 21), r.get(0).date);
        assertEquals("UPI TESTSHOP,CITY GROCERY", r.get(0).description);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals("BBPS PAYMENT RECEIVED", r.get(1).description);
        assertEquals(1000.00, r.get(1).amount, 0.001);
        assertEquals(-1000.00, r.get(2).amount, 0.001);
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertTrue(r.stream().allMatch(x -> "suffix".equals(x.signSource)));
    }

    @Test
    void everyRowCarriesTheCardLast4FromTheCardNoLine() {
        assertTrue(read(statement("", false)).results().stream().allMatch(x -> "1234".equals(x.cardLast4)));
    }

    @Test
    void theEndOfStatementLineEndsTheTableForGood() {
        ReadOutcome o = read(statement("", false));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER")));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("TERMS")));
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void periodAccountNumberAndBankAreFilled() {
        ReadOutcome o = read(statement("", false));
        assertEquals(LocalDate.of(2026, 6, 20), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 7, 18), o.meta().periodEnd);
        assertEquals("451457******1234", o.meta().accountNumber);
        assertEquals("Axis Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theSummaryBoxGivesDueDatesAndAmountsWithCrDrApplied() {
        ReadOutcome o = read(statement("", false));
        assertEquals(LocalDate.of(2026, 8, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(305.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
    }

    @Test
    void aCrMarkedTotalDueIsNegativeAndACrMarkedMinimumDueIsZero() {
        List<Line> lines = statement("", false);
        lines.set(3, ln(0, 237, "3,050.00", 65, "Cr", 100, "305.00", 177, "Cr", 215, "20/06/2026", 259, "-", 299,
                "18/07/2026", 303, "07/08/2026", 393, "18/07/2026", 510));
        ReadOutcome o = read(lines);
        assertEquals(-3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @Test
    void theLimitsBoxGivesCreditAvailableCreditAndCashLimits() {
        ReadOutcome o = read(statement("", false));
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theEquationRowGivesPreviousBalancePaymentsCreditsPurchasesCashAndCharges() {
        ReadOutcome o = read(statement("", false));
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("credits_received"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("cash_advance"), 0.001);
        assertEquals(50.00, (Double) o.summaryFields().get("fees_and_charges"), 0.001);
    }

    @Test
    void aWordInTheTermsTextThatLooksLikeALabelDoesNotStealTheValue() {
        List<Line> lines = statement("", false);
        lines.add(1, ln(0, 100, "For", 40, "hassle", 70, "free", 110, "payments", 150, "register", 200));
        ReadOutcome o = read(lines);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
    }

    @Test
    void theRewardBalanceUnderTheTableIsRead() {
        ReadOutcome o = read(statement("", false));
        assertEquals(1250.0, ((Number) o.summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
        assertFalse(o.summaryFields().containsKey("finance_charges"));
        assertFalse(o.summaryFields().containsKey("reward_points_earned"));
    }

    @Test
    void openingAndClosingReconcileSoTheRowsGetARunningBalance() {
        ReadOutcome o = read(statement("", false));
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(3000.00, o.results().get(0).balance, 0.001);
        assertEquals(2000.00, o.results().get(1).balance, 0.001);
        assertEquals(3050.00, o.results().get(3).balance, 0.001);
        assertEquals(1000.00, o.meta().openingBalance, 0.001);
        assertEquals(3050.00, o.meta().closingBalance, 0.001);
    }

    @Test
    void aPageShiftedLeftStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement("", false)) {
            if (l.top() >= 319 && l.page() == 0) {
                List<Word> ws = new ArrayList<>();
                for (Word w : l.words()) {
                    ws.add(new Word(w.text(), w.x0() - 12, w.x1() - 12, w.top(), w.page()));
                }
                shifted.add(new Line(ws));
            } else {
                shifted.add(l);
            }
        }
        List<RowResult> r = read(shifted).results();
        assertEquals(4, r.size());
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(1000.00, r.get(1).amount, 0.001);
    }

    @Test
    void aRowWithoutAMarkerIsADebitReadByDefault() {
        List<Line> lines = statement("", false);
        lines.set(lines.indexOf(lines.stream().filter(x -> x.top() == 369).findFirst().orElseThrow()), ln(0, 369, "24/06/2026", 35, "ANNUAL", 90, "FEE", 125, "50.00", 560));
        List<RowResult> r = read(lines).results();
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertEquals("default", r.get(3).signSource);
    }

    @Test
    void theProfileDefinitionIsTheAxisCardOfTheCardProduct() {
        LayoutProfile p = PROFILE;
        assertEquals("axis-card-dtda-2024", p.id());
        assertEquals("axis", p.issuerId());
        assertEquals(LayoutProfile.Product.CARD, p.product());
    }
}
