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
 * The HDFC card layout of 2018 to 2020 on a synthetic statement: coordinates as printed, every value invented. Opening
 * 1,000 + purchases 3,000 + finance charges 0 - payments 1,000 = total dues 3,000.
 */
class HdfcCardDtda2018Test {

    private static final LayoutProfile PROFILE = HdfcCardDtda2018.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    /** A dated row (date at x 38, no time): the amount is right-aligned to x 545 and an optional Cr marker follows it. */
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

    /** The statement; the amount label of the table header sits at amountX, with "Reward Points Summary" when withRewards. */
    private static List<Line> statement(double amountX, boolean withRewards) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 27, "Test", 358, "Credit", 452, "Card", 489, "Statement", 519));
        l.add(ln(0, 40, "HDFC", 212, "Bank", 240, "Credit", 265, "Cards", 295));
        l.add(ln(0, 60, "Name", 20, ":", 54, "TEST", 60, "USER", 90, "Statement", 335, "for", 382, "HDFC", 397, "Bank", 426,
                "Credit", 451, "Card", 480));
        l.add(ln(0, 70, "0001000100010001", 2, "Card", 359, "No:", 387, "4000", 401, "12XX", 420, "XXXX", 441, "3456", 463));
        l.add(ln(0, 80, "Address", 20, ":", 54, "NO-1", 60, "ROAD", 100, "Statement", 252, "Date:17/02/2019", 293));
        l.add(ln(0, 102, "Payment", 266, "Due", 303, "Date", 321, "Total", 395, "Dues", 417, "Minimum", 487, "Amount", 525, "Due", 557));
        l.add(ln(0, 113, "GST", 20, "No", 36, ":", 54));
        l.add(ln(0, 125, "In", 17, "case", 27, "you", 57, "wish", 80, "08/03/2019", 285, "3,000.00", 401, "150.00", 517));
        l.add(ln(0, 146, "Credit", 280, "Limit", 306, "Available", 375, "Credit", 412, "Limit", 438, "Available", 491,
                "Cash", 528, "Limit", 550));
        l.add(ln(0, 168, "55,000", 292, "52,000", 405, "20,000", 519));
        l.add(ln(0, 195, "Account", 379, "Summary", 417));
        l.add(ln(0, 207, "Opening", 265, "Payment/", 330, "Purchase/", 396, "Finance", 469));
        l.add(ln(0, 212, "Total", 531, "Dues", 553));
        l.add(ln(0, 217, "Balance", 264, "Credits", 334, "Debits", 404, "Charges", 468));
        l.add(ln(0, 237, "1,000.00", 272, "1,000.00", 340, "3,000.00", 401, "0.00", 478, "3,000.00", 537));
        l.add(ln(0, 266, "Past", 380, "Dues", 402, "(If", 427, "any)", 440));
        l.add(ln(0, 277, "Minimum", 540));
        l.add(ln(0, 282, "Overlimit", 258, "3", 311, "Months+", 318, "2", 370, "Months", 376, "1", 428, "Month", 434,
                "Current", 473, "Dues", 505));
        l.add(ln(0, 287, "Amount", 533, "Due", 566));
        l.add(ln(0, 307, "0.00", 268, "0.00", 324, "0.00", 380, "0.00", 436, "150.00", 485, "150.00", 543));
        l.add(ln(0, 334, "IMPORTANT", 254, "INFORMATION", 303));
        l.add(ln(0, 460, "Domestic", 249, "Transactions", 292));
        l.add(ln(0, 466, "Date", 48, "Transaction", 110, "Description", 164, "Amount", amountX, "(in", amountX + 36, "Rs.)", amountX + 50));
        l.add(ln(0, 471, "TEST", 112, "USER", 140));
        l.add(row(478, "21/01/2019", "UPI TESTSHOP CITY", "2,000.00", false));
        l.add(row(489, "22/01/2019", "NEFT PAYMENT RECEIVED (Ref# 000000000000000001)", "1,000.00", true));
        l.add(row(500, "23/01/2019", "MERCHANT ONE RATE 9.0 -33 (Ref# 000000000000000002)", "1,000.00", false));
        if (withRewards) {
            l.add(ln(0, 697, "Reward", 246, "Points", 282, "Summary", 313));
            l.add(ln(0, 709, "Points", 426, "expiring", 452, "in", 484));
            l.add(ln(0, 714, "Opening", 17, "Balance", 52, "Earned", 120, "Disbursed", 197, "Adjusted/Lapsed", 267,
                    "Closing", 351, "Balance", 383));
            l.add(ln(0, 730, "1,000", 40, "500", 140, "0", 220, "0", 300, "1,500", 380));
            // dated lines after the table are not rows
            l.add(ln(0, 750, "110739256", 20, "13/01/2019", 140, "33,679.26", 250, "9", 330, "1.25", 380, "30,120.35", 430));
        }
        l.add(ln(0, 807, "Page", 278, "1", 298, "of", 304, "1", 313));
        return l;
    }

    private static List<Line> statement() {
        return statement(499, true);
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    private static List<Line> replace(List<Line> lines, double top, Line with) {
        List<Line> out = new ArrayList<>(lines);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).top() == top && out.get(i).page() == 0) {
                out.set(i, with);
                return out;
            }
        }
        throw new IllegalStateException("no line at " + top);
    }

    @Test
    void theProfileMatchesThisLayoutForHdfc() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(), "hdfc").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        assertNull(LayoutRegistry.defaults().match(statement(), "axis"));
    }

    @Test
    void aStatementWithoutARewardPointsSummaryIsNotThisLayout() {
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(statement(499, false), "hdfc"));
    }

    @Test
    void theLaterGenerationWithItsAmountLabelFurtherRightIsNotThisLayout() {
        List<Line> lines = statement(504, true);
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(lines, "hdfc"));
    }

    @Test
    void aHeaderWithADateAndTimeColumnIsAnotherLayout() {
        List<Line> lines = replace(statement(), 466, ln(0, 466, "Date", 48, "&TIME", 70, "Transaction", 110, "Description", 164,
                "Amount", 499, "(in", 535, "Rs.)", 549));
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(lines, "hdfc"));
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromTheCrMarker() {
        List<RowResult> r = read(statement()).results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2019, 1, 21), r.get(0).date);
        assertEquals("UPI TESTSHOP CITY", r.get(0).description);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2019, 1, 22), r.get(1).date);
        assertEquals("NEFT PAYMENT RECEIVED (Ref# 000000000000000001)", r.get(1).description);
        assertEquals(1000.00, r.get(1).amount, 0.001);
        assertEquals(-1000.00, r.get(2).amount, 0.001);
        assertEquals("default", r.get(0).signSource);
        assertEquals("suffix", r.get(1).signSource);
    }

    @Test
    void anAmountLikeTokenInsideTheNarrationStaysInTheNarration() {
        assertEquals("MERCHANT ONE RATE 9.0 -33 (Ref# 000000000000000002)", read(statement()).results().get(2).description);
    }

    @Test
    void everyRowCarriesTheLast4OfTheCardNumber() {
        assertTrue(read(statement()).results().stream().allMatch(x -> "3456".equals(x.cardLast4)));
    }

    @Test
    void theCardholderNameLineBetweenHeaderAndRowsIsNotARow() {
        ReadOutcome o = read(statement());
        assertEquals(3, o.results().size());
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void theRewardPointsSummaryEndsTheTableSoALaterDatedLineIsNotRead() {
        ReadOutcome o = read(statement());
        assertTrue(o.results().stream().noneMatch(x -> x.date.equals(LocalDate.of(2019, 1, 13))));
    }

    @Test
    void aRowWithoutAMarkerIsADebitReadByDefault() {
        List<Line> lines = replace(statement(), 478, ln(0, 478, "21/01/2019", 38, "UPI", 112, "SHOP", 140, "2,000.00", 515));
        RowResult r = read(lines).results().get(0);
        assertEquals(-2000.00, r.amount, 0.001);
        assertEquals("default", r.signSource);
    }

    @Test
    void theStatementDateIsThePeriodEndAndThePeriodStartStaysEmpty() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2019, 2, 17), o.meta().periodEnd);
        assertNull(o.meta().periodStart);
        assertEquals(LocalDate.of(2019, 2, 17), o.meta().documentDate);
    }

    @Test
    void theCardNumberWithItsSpacesIsTheAccountNumberAndTheBankIsStated() {
        ReadOutcome o = read(statement());
        assertEquals("4000 12XX XXXX 3456", o.meta().accountNumber);
        assertEquals("HDFC Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void aCardNumberPrintedWithThreeGroupsOfThePageEdgeIsStillTheAccountNumber() {
        List<Line> lines = replace(statement(), 70, ln(0, 70, "Card", 359, "No:", 387, "4000", 401, "12XX", 420, "XXX", 441));
        assertEquals("4000 12XX XXX", read(lines).meta().accountNumber);
    }

    @Test
    void theDuesRowGivesTheDueDateTotalDuesAndMinimumDue() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2019, 3, 8), o.summaryFields().get("payment_due_date"));
        assertEquals(3000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(150.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
    }

    @Test
    void aCrMarkedTotalDueIsNegativeAndACrMarkedMinimumDueIsZero() {
        List<Line> lines = replace(statement(), 125, ln(0, 125, "In", 17, "case", 27, "08/03/2019", 285, "3,000.00", 401,
                "Cr", 445, "150.00", 517, "Cr", 556));
        ReadOutcome o = read(lines);
        assertEquals(-3000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @Test
    void theLimitsPrintedAsWholeRupeesAreRead() {
        ReadOutcome o = read(statement());
        assertEquals(55000.0, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(52000.0, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.0, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theAccountSummaryGivesOpeningPaymentsPurchasesAndFinanceCharges() {
        ReadOutcome o = read(statement());
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("credits_received"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("finance_charges"), 0.0);
    }

    @Test
    void aNonZeroFinanceChargeIsRead() {
        List<Line> lines = replace(statement(), 237, ln(0, 237, "1,000.00", 272, "1,000.00", 340, "3,000.00", 401,
                "50.00", 478, "3,050.00", 537));
        assertEquals(50.00, (Double) read(lines).summaryFields().get("finance_charges"), 0.001);
    }

    @Test
    void theOverlimitFigureOfThePastDuesRowIsRead() {
        assertEquals(0.0, (Double) read(statement()).summaryFields().get("overlimit_amount"), 0.0);
        List<Line> lines = replace(statement(), 307, ln(0, 307, "75.00", 268, "0.00", 324, "0.00", 380, "0.00", 436,
                "150.00", 485, "150.00", 543));
        assertEquals(75.00, (Double) read(lines).summaryFields().get("overlimit_amount"), 0.001);
    }

    @Test
    void theRewardPointsSummaryGivesPointsEarnedAndTheClosingBalance() {
        ReadOutcome o = read(statement());
        assertEquals(500.0, ((Number) o.summaryFields().get("reward_points_earned")).doubleValue(), 0.001);
        assertEquals(1500.0, ((Number) o.summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
    }

    @Test
    void paymentsAndCreditsAreOneFigureSoNoSeparatePaymentsOrFeesFieldIsFilled() {
        ReadOutcome o = read(statement());
        assertFalse(o.summaryFields().containsKey("payments_received"));
        assertFalse(o.summaryFields().containsKey("fees_and_charges"));
    }

    @Test
    void openingAndClosingReconcileSoTheRowsGetARunningBalance() {
        ReadOutcome o = read(statement());
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(3000.00, o.results().get(0).balance, 0.001);
        assertEquals(2000.00, o.results().get(1).balance, 0.001);
        assertEquals(3000.00, o.results().get(2).balance, 0.001);
        assertEquals(1000.00, o.meta().openingBalance, 0.001);
        assertEquals(3000.00, o.meta().closingBalance, 0.001);
    }

    @Test
    void aPageShiftedLeftWithinTheAmountLabelRangeStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement()) {
            if (l.top() >= 466 && l.top() < 600) {
                List<Word> ws = new ArrayList<>();
                for (Word w : l.words()) {
                    ws.add(new Word(w.text(), w.x0() - 5, w.x1() - 5, w.top(), w.page()));
                }
                shifted.add(new Line(ws));
            } else {
                shifted.add(l);
            }
        }
        List<RowResult> r = read(shifted).results();
        assertEquals(3, r.size());
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(1000.00, r.get(1).amount, 0.001);
    }

    @Test
    void aPageShiftedLeftBeyondTheAmountLabelRangeIsNotThisLayout() {
        List<Line> lines = replace(statement(), 466, ln(0, 466, "Date", 36, "Transaction", 98, "Description", 152, "Amount", 487,
                "(in", 523, "Rs.)", 537));
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(lines, "hdfc"));
    }

    @Test
    void theProfileDefinitionIsTheHdfcCardOfTheCardProduct() {
        assertEquals("hdfc-card-dtda-2018", PROFILE.id());
        assertEquals("hdfc", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
