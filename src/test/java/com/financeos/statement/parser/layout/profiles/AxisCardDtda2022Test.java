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
 * The Axis card layout of 2022-2023 on a synthetic statement: coordinates as printed (letterspaced label words, a
 * merchant category that may wrap onto the lines around its row), every value invented. Previous balance 1,000 Dr
 * - payments 1,000 - credits 0 + purchases 3,000 + cash 0 + other 50 = total due 3,050 Dr.
 */
class AxisCardDtda2022Test {

    private static final LayoutProfile PROFILE = AxisCardDtda2022.build();
    private static final LayoutRegistry ONLY_THIS = new LayoutRegistry(List.of(PROFILE));

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static Line periodLabelLine(boolean splitWords) {
        return splitWords
                ? ln(0, 224, "Total", 45, "Payment", 68, "Due", 105, "Minimum", 144, "Payment", 183, "Due", 220,
                        "Statement", 265, "Per", 308, "iod", 322, "Payment", 375, "Due", 412, "Date", 430,
                        "Statement", 472, "Generat", 515, "ion", 547, "Date", 563)
                : ln(0, 224, "Total", 45, "Payment", 68, "Due", 105, "Minimum", 144, "Payment", 183, "Due", 220,
                        "Statement", 265, "Period", 308, "Payment", 375, "Due", 412, "Date", 430,
                        "Statement", 472, "Generation", 515, "Date", 563);
    }

    /** The statement; headerExtra is appended to the table header line (e.g. the cashback column of another layout). */
    private static List<Line> statement(String headerExtra, boolean splitWords) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 60, "Airtel", 282, "Axis", 319, "Bank", 348, "Credit", 382, "Card", 422, "Statement", 454));
        l.add(ln(0, 210, "PAYMENT", 251, "SUMMARY", 297));
        l.add(periodLabelLine(splitWords));
        l.add(ln(0, 237, "3,050.00", 61, "Dr", 95, "305.00", 169, "Dr", 204, "20/06/2026", 254, "-", 299,
                "18/07/2026", 304, "07/08/2026", 389, "18/07/2026", 507));
        l.add(ln(0, 253, "Credit", 43, "Card", 70, "Number", 91, "Credit", 167, "L", 194, "imit", 198,
                "Avai", 257, "lable", 274, "Credit", 296, "L", 323, "imit", 327, "Avai", 372, "lable", 389,
                "Cash", 411, "L", 432, "imit", 436));
        l.add(ln(0, 266, "451457******1234", 46, "100,000.00", 170, "97,000.00", 281, "20,000.00", 391));
        l.add(ln(0, 282, "Previous", 34, "Balance", 70, "-", 102, "Payments", 108, "-", 148, "Credits", 154, "+", 184,
                "Purchase", 191, "+", 229, "Cash", 236, "Advance", 257, "+", 292, "Other", 300, "Debit&Charges", 325,
                "=Total", 385, "Payment", 412, "Due", 449));
        l.add(ln(0, 295, "1,000.00", 49, "Dr", 76, "1,000.00", 120, "0.00", 168, "3,000.00", 211, "0.00", 270,
                "50.00", 334, "3,050.00", 405, "Dr", 439));
        l.add(ln(0, 307, "Account", 266, "Summary", 307));
        l.add(headerExtra.isEmpty()
                ? ln(0, 318, "DATE", 49, "TRANSACTION", 179, "DETAILS", 234, "MERCHANT", 383, "CATEGORY", 427,
                        "AMOUNT", 512, "(Rs.)", 548)
                : ln(0, 318, "DATE", 49, "TRANSACTION", 179, "DETAILS", 234, "MERCHANT", 383, "CATEGORY", 427,
                        "AMOUNT", 512, "(Rs.)", 548, headerExtra, 600));
        l.add(ln(0, 328, "Card", 85, "No:", 102, "451457******1234", 122, "Name", 260, "TEST", 287, "USER", 317));
        l.add(ln(0, 338, "21/06/2026", 35, "TESTSHOP,CITY", 90, "GROCERY", 360, "2,000.00", 545, "Dr", 570));
        l.add(ln(0, 348, "22/06/2026", 35, "BBPS", 90, "PAYMENT", 120, "RECEIVED", 160, "1,000.00", 545, "Cr", 570));
        l.add(ln(0, 358, "23/06/2026", 35, "MERCHANT", 90, "ONE", 140, "TRAVEL", 360, "1,000.00", 545, "Dr", 570));
        l.add(ln(0, 368, "24/06/2026", 35, "ANNUAL", 90, "FEE", 125, "50.00", 553, "Dr", 570));
        l.add(ln(0, 380, "****", 248, "End", 269, "of", 287, "Statement", 297, "****", 341));
        l.add(ln(0, 392, "25/06/2026", 35, "AFTER", 90, "THE", 120, "END", 140, "9,999.00", 545, "Dr", 570));
        l.add(ln(0, 643, "CASHBACK", 266, "DETAILS", 316));
        l.add(ln(0, 657, "Cashback", 123, "Earned", 170, "Cashback", 397, "Credited", 444));
        l.add(ln(0, 669, "20.00", 150, "10.00", 430));
        l.add(ln(1, 60, "Airtel", 282, "Axis", 319, "Bank", 348, "Credit", 382, "Card", 422, "Statement", 454));
        l.add(ln(1, 90, "26/06/2026", 35, "TERMS", 90, "TEXT", 130, "8,888.00", 545, "Dr", 570));
        return l;
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    private static Line at(List<Line> lines, double top) {
        return lines.stream().filter(x -> x.page() == 0 && x.top() == top).findFirst().orElseThrow();
    }

    @Test
    void theProfileMatchesTheAxisLayoutOfThisStatement() {
        assertEquals(PROFILE.id(), ONLY_THIS.match(statement("", true), "axis").id());
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement("", true), "axis").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        assertNull(ONLY_THIS.match(statement("", true), "hdfc"));
    }

    @Test
    void theWholeWordGenerationOfTheSameTableIsTheOtherAxisLayout() {
        assertNull(ONLY_THIS.match(statement("", false), "axis"));
        assertEquals(AxisCardDtda2024.ID, LayoutRegistry.defaults().match(statement("", false), "axis").id());
    }

    @Test
    void theCashbackColumnVariantIsAnotherLayout() {
        assertNull(ONLY_THIS.match(statement("CASHBACK", true), "axis"));
        assertNull(LayoutRegistry.defaults().match(statement("CASHBACK", true), "axis"));
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromTheCrDrMarker() {
        List<RowResult> r = read(statement("", true)).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 6, 21), r.get(0).date);
        assertEquals("TESTSHOP,CITY GROCERY", r.get(0).description);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 6, 22), r.get(1).date);
        assertEquals("BBPS PAYMENT RECEIVED", r.get(1).description);
        assertEquals(1000.00, r.get(1).amount, 0.001);
        assertEquals(-1000.00, r.get(2).amount, 0.001);
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertTrue(r.stream().allMatch(x -> "suffix".equals(x.signSource)));
    }

    @Test
    void everyRowCarriesTheCardLast4FromTheCardNoLine() {
        assertTrue(read(statement("", true)).results().stream().allMatch(x -> "1234".equals(x.cardLast4)));
    }

    @Test
    void theEndOfStatementLineEndsTheTableForGood() {
        ReadOutcome o = read(statement("", true));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER")));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("TERMS")));
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void aStatementWithoutTheEndLineEndsAtTheCashbackBoxWhoseFiguresAreNotRows() {
        List<Line> lines = statement("", true);
        lines.remove(at(lines, 380));
        lines.remove(at(lines, 392));
        lines.removeIf(x -> x.page() == 1);
        ReadOutcome o = read(lines);
        assertEquals(4, o.results().size());
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void aMerchantCategoryWrappedAboveAndBelowItsRowJoinsTheNarration() {
        List<Line> lines = statement("", true);
        lines.add(lines.indexOf(at(lines, 358)), ln(0, 354, "MOBILE", 360, "PHONES", 395));
        lines.add(lines.indexOf(at(lines, 358)) + 1, ln(0, 362, "BILLS", 360));
        List<RowResult> r = read(lines).results();
        assertEquals(4, r.size());
        assertEquals("MOBILE PHONES MERCHANT ONE TRAVEL BILLS", r.get(2).description);
        assertEquals("TESTSHOP,CITY GROCERY", r.get(0).description);
        assertEquals("ANNUAL FEE", r.get(3).description);
    }

    @Test
    void aSecondPageWithoutTheHeaderContinuesTheTable() {
        List<Line> lines = statement("", true);
        lines.remove(at(lines, 380));
        lines.remove(at(lines, 392));
        lines.removeIf(x -> x.page() == 1 || x.top() >= 643); // the cashback box comes after the last page's table
        lines.add(ln(1, 60, "Airtel", 282, "Axis", 319, "Bank", 348, "Credit", 382, "Card", 422, "Statement", 454));
        lines.add(ln(1, 90, "26/06/2026", 35, "PAGE", 90, "TWO", 120, "STORE", 360, "70.00", 553, "Dr", 570));
        lines.add(ln(1, 102, "****", 248, "End", 269, "of", 287, "Statement", 297, "****", 341));
        List<RowResult> r = read(lines).results();
        assertEquals(5, r.size());
        assertEquals(LocalDate.of(2026, 6, 26), r.get(4).date);
        assertEquals(-70.00, r.get(4).amount, 0.001);
        assertEquals("1234", r.get(4).cardLast4);
    }

    @Test
    void periodAccountNumberAndBankAreFilled() {
        ReadOutcome o = read(statement("", true));
        assertEquals(LocalDate.of(2026, 6, 20), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 7, 18), o.meta().periodEnd);
        assertEquals("451457******1234", o.meta().accountNumber);
        assertEquals("Axis Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theSummaryBoxGivesDueDatesAndAmountsWithCrDrApplied() {
        ReadOutcome o = read(statement("", true));
        assertEquals(LocalDate.of(2026, 8, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(305.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
    }

    @Test
    void aCrMarkedTotalDueIsNegativeAndACrMarkedMinimumDueIsZero() {
        List<Line> lines = statement("", true);
        lines.set(lines.indexOf(at(lines, 237)), ln(0, 237, "3,050.00", 61, "Cr", 95, "305.00", 169, "Cr", 204,
                "20/06/2026", 254, "-", 299, "18/07/2026", 304, "07/08/2026", 389, "18/07/2026", 507));
        ReadOutcome o = read(lines);
        assertEquals(-3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @Test
    void theLimitsBoxReadsTheLetterspacedLabelsIntoTheThreeLimits() {
        ReadOutcome o = read(statement("", true));
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theLimitLabelsAlsoReadWhenPrintedWholeOrWithOtherBreaks() {
        List<Line> lines = statement("", true);
        lines.set(lines.indexOf(at(lines, 253)), ln(0, 253, "Credit", 43, "Card", 70, "Number", 91, "Credit", 167,
                "Limit", 194, "Available", 257, "Credit", 296, "Limit", 323, "Available", 372, "Cash", 411,
                "Limit", 432));
        ReadOutcome o = read(lines);
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theEquationRowGivesPreviousBalancePaymentsCreditsPurchasesCashAndCharges() {
        ReadOutcome o = read(statement("", true));
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("credits_received"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("cash_advance"), 0.001);
        assertEquals(50.00, (Double) o.summaryFields().get("fees_and_charges"), 0.001);
    }

    @Test
    void aCrMarkedPreviousBalanceIsNegative() {
        List<Line> lines = statement("", true);
        lines.set(lines.indexOf(at(lines, 295)), ln(0, 295, "1,000.00", 49, "Cr", 76, "1,000.00", 120, "0.00", 168,
                "3,000.00", 211, "0.00", 270, "50.00", 334, "3,050.00", 405, "Dr", 439));
        assertEquals(-1000.00, (Double) read(lines).summaryFields().get("previous_balance"), 0.001);
    }

    @Test
    void aWordInTheTermsTextThatLooksLikeALabelDoesNotStealTheValue() {
        List<Line> lines = statement("", true);
        lines.add(1, ln(0, 100, "For", 40, "hassle", 70, "free", 110, "payments", 150, "register", 200));
        assertEquals(1000.00, (Double) read(lines).summaryFields().get("payments_received"), 0.001);
    }

    @Test
    void thisGenerationPrintsNoRewardFinanceChargeOrPointsEarnedFigure() {
        ReadOutcome o = read(statement("", true));
        assertFalse(o.summaryFields().containsKey("reward_points_balance"));
        assertFalse(o.summaryFields().containsKey("finance_charges"));
        assertFalse(o.summaryFields().containsKey("reward_points_earned"));
        assertTrue(PROFILE.notPrinted().containsAll(
                List.of("reward_points_balance", "finance_charges", "reward_points_earned")));
    }

    @Test
    void openingAndClosingReconcileSoTheRowsGetARunningBalance() {
        ReadOutcome o = read(statement("", true));
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
        for (Line l : statement("", true)) {
            if (l.top() >= 318 && l.page() == 0) {
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
        List<Line> lines = statement("", true);
        lines.set(lines.indexOf(at(lines, 368)), ln(0, 368, "24/06/2026", 35, "ANNUAL", 90, "FEE", 125, "50.00", 553));
        List<RowResult> r = read(lines).results();
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertEquals("default", r.get(3).signSource);
    }

    @Test
    void theProfileDefinitionIsTheAxisCardOfTheCardProduct() {
        assertEquals("axis-card-dtda-2022", PROFILE.id());
        assertEquals("axis", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
