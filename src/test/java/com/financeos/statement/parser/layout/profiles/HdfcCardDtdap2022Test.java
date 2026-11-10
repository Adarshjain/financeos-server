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
 * The HDFC card layout with a time and a reward-points column, on a synthetic statement: coordinates as printed, every
 * value invented. Opening 1,000 + purchases 3,000 + finance charges 0 - payments 1,000 = total dues 3,000.
 */
class HdfcCardDtdap2022Test {

    private static final LayoutProfile PROFILE = HdfcCardDtdap2022.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    /** A dated row: the amount is right-aligned to x 556 and an optional Cr marker follows it. */
    private static Line row(double top, String date, String time, String narration, String points, String amount,
                            boolean credit) {
        List<Object> w = new ArrayList<>(List.of(date, 18, time, 57));
        double x = 110;
        for (String t : narration.split(" ")) {
            w.add(t);
            w.add(x);
            x += 5.2 * t.length() + 5;
        }
        if (points != null) {
            for (String t : points.split(" ")) {
                w.add(t);
                w.add(t.equals("-") ? 436.0 : 446.0);
            }
        }
        w.add(amount);
        w.add(556 - 5.2 * amount.length());
        if (credit) {
            w.add("Cr");
            w.add(559);
        }
        return ln(0, top, w.toArray());
    }

    /** The statement; the header line is the plain one, headerExtra is appended to it. */
    private static List<Line> statement(boolean featureColumn, String headerExtra) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 21, "Test", 379, "Regalia", 406, "Credit", 451, "Card", 488, "Statement", 518));
        l.add(ln(0, 41, "HDFC", 237, "Bank", 266, "Credit", 291, "Cards", 321, "GSTIN", 350, ":", 381, "00AAAAA0000A0Z0", 386));
        l.add(ln(0, 58, "Name", 20, ":", 52, "TEST", 57, "USER", 80, "Statement", 336, "for", 383, "HDFC", 398, "Bank", 427,
                "Credit", 452, "Card", 481));
        l.add(ln(0, 70, "0001000100010001", 2, "Card", 360, "No:", 388, "4000", 402, "12XX", 421, "XXXX", 442, "3456", 464));
        l.add(ln(0, 83, "Address", 20, ":", 52, "NO-1", 57, "TEST", 89, "ROAD", 120, "Statement", 253, "Date:17/02/2026", 294));
        l.add(ln(0, 102, "Payment", 267, "Due", 304, "Date", 322, "Total", 396, "Dues", 418, "Minimum", 488, "Amount", 526, "Due", 558));
        l.add(ln(0, 113, "GST", 20, "No", 36, ":", 52));
        l.add(ln(0, 125, "In", 17, "case", 27, "you", 57, "wish", 80, "08/03/2026", 285, "3,000.00", 401, "150.00", 517));
        l.add(ln(0, 146, "Credit", 281, "Limit", 307, "Available", 376, "Credit", 413, "Limit", 439, "Available", 492,
                "Cash", 529, "Limit", 551));
        l.add(ln(0, 168, "7,61,000", 290, "7,37,333", 403, "3,04,400", 517));
        l.add(ln(0, 195, "Account", 379, "Summary", 417));
        l.add(ln(0, 207, "Opening", 265, "Payment/", 330, "Purchase/", 396, "Finance", 469));
        l.add(ln(0, 212, "Total", 532, "Dues", 554));
        l.add(ln(0, 217, "Balance", 265, "Credits", 335, "Debits", 405, "Charges", 469));
        l.add(ln(0, 237, "1,000.00", 272, "1,000.00", 340, "3,000.00", 401, "0.00", 478, "3,000.00", 537));
        l.add(ln(0, 266, "Past", 380, "Dues", 402, "(If", 427, "any)", 440));
        l.add(ln(0, 277, "Minimum", 540));
        l.add(ln(0, 282, "Overlimit", 258, "3", 311, "Months+", 318, "2", 370, "Months", 376, "1", 428, "Month", 434,
                "Current", 473, "Dues", 505));
        l.add(ln(0, 287, "Amount", 533, "Due", 566));
        l.add(ln(0, 307, "0.00", 268, "0.00", 324, "0.00", 380, "0.00", 436, "150.00", 485, "150.00", 543));
        l.add(ln(0, 334, "IMPORTANT", 254, "INFORMATION", 303));
        l.add(ln(0, 426, "Domestic", 249, "Transactions", 292));
        if (featureColumn) {
            l.add(ln(0, 438, "Feature", 411, "Reward", 447));
        }
        l.add(headerExtra.isEmpty()
                ? ln(0, 444, "Date", 48, "Transaction", 110, "Description", 164, "Amount", 504, "(in", 540, "Rs.)", 554)
                : ln(0, 444, "Date", 48, headerExtra, 70, "Transaction", 110, "Description", 164, "Amount", 504, "(in", 540, "Rs.)", 554));
        if (featureColumn) {
            l.add(ln(0, 449, "Points", 432));
        }
        l.add(ln(0, 471, "TEST", 112, "USER", 140));
        l.add(row(483, "21/01/2026", "10:15:30", "UPI TESTSHOP CITY", "12", "2,000.00", false));
        l.add(row(494, "22/01/2026", "11:00:00", "NEFT PAYMENT RECEIVED (Ref# 000000000000000001)", null, "1,000.00", true));
        l.add(row(505, "23/01/2026", "12:30:45", "MERCHANT ONE RATE 9.0 -33 (Ref# 000000000000000002)", "- 5", "1,000.00", false));
        l.add(ln(0, 785, "Reward", 246, "Points", 282, "Summary", 313));
        l.add(ln(0, 799, "Feature", 106, "+", 138, "Bonus", 146, "Points", 432, "expiring", 458, "in", 491));
        l.add(ln(0, 804, "Opening", 17, "Balance", 53, "Disbursed", 207, "Adjusted/Lapsed", 276, "Closing", 355, "Balance", 387));
        l.add(ln(0, 809, "Reward", 95, "Points", 127, "Earned", 154, "next", 442, "30", 460, "days", 471));
        l.add(ln(0, 826, "2,397", 42, "468", 132, "0", 224, "0", 307, "2,865", 378, "0", 463, "0", 546));
        // dated lines after the table (a loan summary) are not rows
        l.add(ln(0, 840, "110739256", 20, "13/01/2026", 140, "33,679.26", 250, "9", 330, "1.25", 380, "30,120.35", 430));
        l.add(ln(0, 895, "Page", 278, "1", 298, "of", 304, "1", 313));
        return l;
    }

    private static List<Line> statement() {
        return statement(true, "");
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
    void aStatementWithoutTheRewardPointsColumnIsNotThisLayout() {
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(statement(false, ""), "hdfc"));
    }

    @Test
    void aHeaderWithADateAndTimeColumnIsAnotherLayout() {
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(statement(true, "&TIME"), "hdfc"));
    }

    @Test
    void theEarlierGenerationWithItsAmountLabelFurtherLeftIsNotRoutedHere() {
        List<Line> lines = replace(statement(false, ""), 444,
                ln(0, 444, "Date", 48, "Transaction", 110, "Description", 164, "Amount", 499, "(in", 535, "Rs.)", 549));
        assertEquals("hdfc-card-dtda-2018", LayoutRegistry.defaults().match(lines, "hdfc").id());
        assertNull(new LayoutRegistry(List.of(PROFILE)).match(lines, "hdfc"));
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromTheCrMarker() {
        List<RowResult> r = read(statement()).results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2026, 1, 21), r.get(0).date);
        assertEquals("UPI TESTSHOP CITY", r.get(0).description);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 1, 22), r.get(1).date);
        assertEquals("NEFT PAYMENT RECEIVED (Ref# 000000000000000001)", r.get(1).description);
        assertEquals(1000.00, r.get(1).amount, 0.001);
        assertEquals(-1000.00, r.get(2).amount, 0.001);
        assertEquals("default", r.get(0).signSource);
        assertEquals("suffix", r.get(1).signSource);
        assertEquals("default", r.get(2).signSource);
    }

    @Test
    void theTimeAndTheRewardPointsOfARowAreNotPartOfItsNarrationOrAmount() {
        List<RowResult> r = read(statement()).results();
        assertFalse(r.get(0).description.contains("10:15"));
        assertFalse(r.get(0).description.endsWith("12"));
        assertFalse(r.get(2).description.endsWith("5"));
        assertEquals(-1000.00, r.get(2).amount, 0.001);
    }

    @Test
    void anAmountLikeTokenInsideTheNarrationIsLeftOutLikeTheGenericReaderDoes() {
        assertEquals("MERCHANT ONE RATE -33 (Ref# 000000000000000002)", read(statement()).results().get(2).description);
    }

    @Test
    void everyRowCarriesTheLast4OfTheCardNumber() {
        assertTrue(read(statement()).results().stream().allMatch(x -> "3456".equals(x.cardLast4)));
    }

    @Test
    void theHeaderLessCardholderNameLineBetweenHeaderAndRowsIsNotARow() {
        ReadOutcome o = read(statement());
        assertEquals(3, o.results().size());
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void theRewardPointsSummaryEndsTheTableSoALoanSummaryRowIsNotRead() {
        ReadOutcome o = read(statement());
        assertEquals(3, o.results().size());
        assertTrue(o.results().stream().noneMatch(x -> x.date.equals(LocalDate.of(2026, 1, 13))));
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void aHeaderLessPageContinuesTheTableWithTheSameColumns() {
        List<Line> lines = new ArrayList<>();
        List<Line> tail = new ArrayList<>();
        for (Line l : statement()) {
            if (l.top() >= 785 && l.top() < 895) {
                tail.add(new Line(l.words().stream().map(w -> new Word(w.text(), w.x0(), w.x1(), w.top() - 700, 1)).toList()));
            } else {
                lines.add(l);
            }
        }
        lines.add(ln(1, 90, "25/01/2026", 18, "09:00:00", 57, "ANOTHER", 110, "SHOP", 150, "50.00", 515));
        lines.addAll(tail);
        List<RowResult> r = read(lines).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 1, 25), r.get(3).date);
        assertEquals("ANOTHER SHOP", r.get(3).description);
        assertEquals(-50.00, r.get(3).amount, 0.001);
    }

    @Test
    void aRowWithoutAMarkerIsADebitReadByDefault() {
        List<Line> lines = replace(statement(), 483, ln(0, 483, "21/01/2026", 18, "10:15:30", 57, "UPI", 110, "SHOP", 140, "2,000.00", 515));
        RowResult r = read(lines).results().get(0);
        assertEquals(-2000.00, r.amount, 0.001);
        assertEquals("default", r.signSource);
    }

    @Test
    void theStatementDateIsThePeriodEndAndThePeriodStartStaysEmpty() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 2, 17), o.meta().periodEnd);
        assertNull(o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 2, 17), o.meta().documentDate);
    }

    @Test
    void theCardNumberWithItsSpacesIsTheAccountNumberAndTheBankIsStated() {
        ReadOutcome o = read(statement());
        assertEquals("4000 12XX XXXX 3456", o.meta().accountNumber);
        assertEquals("HDFC Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theDuesRowGivesTheDueDateTotalDuesAndMinimumDue() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 3, 8), o.summaryFields().get("payment_due_date"));
        assertEquals(3000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(150.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
    }

    @Test
    void aCrMarkedTotalDueIsNegativeAndACrMarkedMinimumDueIsZero() {
        List<Line> lines = replace(statement(), 125, ln(0, 125, "In", 17, "case", 27, "08/03/2026", 285, "3,000.00", 401,
                "Cr", 445, "150.00", 517, "Cr", 556));
        ReadOutcome o = read(lines);
        assertEquals(-3000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @Test
    void theLimitsInLakhGroupingWithoutDecimalsAreRead() {
        ReadOutcome o = read(statement());
        assertEquals(761000.0, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(737333.0, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(304400.0, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
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
        assertEquals(468.0, ((Number) o.summaryFields().get("reward_points_earned")).doubleValue(), 0.001);
        assertEquals(2865.0, ((Number) o.summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
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
    void aPageShiftedLeftStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement()) {
            if (l.top() >= 444 && l.top() < 700) {
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
        assertEquals(3, r.size());
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(1000.00, r.get(1).amount, 0.001);
    }

    @Test
    void theProfileDefinitionIsTheHdfcCardOfTheCardProduct() {
        assertEquals("hdfc-card-dtdap-2022", PROFILE.id());
        assertEquals("hdfc", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
