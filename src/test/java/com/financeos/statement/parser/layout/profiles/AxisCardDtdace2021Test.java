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
 * The Axis card layout of 2021-2023 with the CASHBACK EARNED column on a synthetic statement: coordinates as printed
 * (letterspaced label words, amounts left-aligned under AMOUNT), every value invented. Previous balance 1,000 Dr -
 * payments 1,000 - credits 0 + purchases 3,000 + cash 0 + other 50 = total due 3,050 Dr. Each row also carries a
 * cashback figure with its own Cr / Dr marker.
 */
class AxisCardDtdace2021Test {

    private static final LayoutProfile PROFILE = AxisCardDtdace2021.build();
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

    private static Line header(int page, double top) {
        return ln(page, top, "DATE", 48, "TRANSACTION", 132, "DETAILS", 187, "MERCHANT", 289, "CATEGORY", 330,
                "AMOUNT", 416, "(Rs.)", 452, "CASHBACK", 501, "EARNED", 543);
    }

    private static Line row(int page, double top, String date, String narr, String cat, String amt, String mk,
                            String cb, String cbMk) {
        List<Word> words = new ArrayList<>(ln(page, top, date, 34, narr, 89).words());
        if (!cat.isEmpty()) {
            words.addAll(ln(page, top, cat, 266).words());
        }
        words.addAll(ln(page, top, amt, 402, mk, 402 + 5.2 * amt.length() + 3, cb, 490, cbMk, 515).words());
        return new Line(words);
    }

    private static List<Line> summaryBox(boolean splitWords) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 60, "Axis", 288, "Bank", 316, "Credit", 376, "Card", 417, "Statement", 448));
        l.add(ln(0, 210, "PAYMENT", 251, "SUMMARY", 297));
        l.add(splitWords
                ? ln(0, 224, "Total", 45, "Payment", 68, "Due", 105, "Minimum", 144, "Payment", 184, "Due", 221,
                        "Statement", 265, "Per", 309, "iod", 323, "Payment", 376, "Due", 413, "Date", 431,
                        "Statement", 472, "Generat", 516, "ion", 548, "Date", 563)
                : ln(0, 224, "Total", 45, "Payment", 68, "Due", 105, "Minimum", 144, "Payment", 184, "Due", 221,
                        "Statement", 265, "Period", 309, "Payment", 376, "Due", 413, "Date", 431,
                        "Statement", 472, "Generation", 516, "Date", 563));
        l.add(ln(0, 238, "3,050.00", 59, "Dr", 99, "305.00", 170, "Dr", 204, "20/06/2026", 254, "-", 299,
                "18/07/2026", 305, "07/08/2026", 390, "18/07/2026", 507));
        l.add(ln(0, 253, "Credit", 43, "Card", 70, "Number", 91, "Credit", 167, "L", 194, "imit", 198,
                "Avai", 257, "lable", 274, "Credit", 296, "L", 323, "imit", 327, "Avai", 372, "lable", 389,
                "Cash", 411, "L", 432, "imit", 436));
        l.add(ln(0, 266, "451457******1234", 46, "100,000.00", 170, "97,000.00", 281, "20,000.00", 391));
        l.add(ln(0, 282, "Previous", 34, "Balance", 70, "-", 103, "Payments", 109, "-", 149, "Credits", 154, "+", 185,
                "Purchase", 192, "+", 230, "Cash", 237, "Advance", 258, "+", 293, "Other", 300, "Debit&Charges", 325,
                "=Total", 386, "Payment", 413, "Due", 449));
        l.add(ln(0, 295, "1,000.00", 49, "Dr", 76, "1,000.00", 120, "0.00", 168, "3,000.00", 211, "0.00", 270,
                "50.00", 334, "3,050.00", 405, "Dr", 439));
        l.add(ln(0, 307, "Account", 266, "Summary", 307));
        return l;
    }

    private static List<Line> statement(boolean splitWords) {
        List<Line> l = summaryBox(splitWords);
        l.add(header(0, 320));
        l.add(ln(0, 331, "Card", 39, "No:", 59, "451457******1234", 118, "Name", 264, "TEST", 301, "USER", 334));
        l.add(row(0, 342, "21/06/2026", "UPI TESTSHOP,CITY", "GROCERY", "2,000.00", "Dr", "20.00", "Cr"));
        l.add(row(0, 353, "22/06/2026", "BBPS PAYMENT RECEIVED", "", "1,000.00", "Cr", "0.00", "Dr"));
        l.add(row(0, 364, "23/06/2026", "MERCHANT ONE", "TRAVEL", "1,000.00", "Dr", "10.00", "Cr"));
        l.add(row(0, 375, "24/06/2026", "ANNUAL FEE", "", "50.00", "Dr", "0.00", "Cr"));
        l.add(ln(0, 387, "****", 251, "End", 272, "of", 289, "Statement", 300, "****", 344));
        l.add(row(0, 399, "25/06/2026", "AFTER THE END", "", "9,999.00", "Dr", "0.00", "Cr"));
        addCashbackBox(l, 0);
        l.add(ln(1, 60, "Axis", 288, "Bank", 316, "Credit", 376, "Card", 417, "Statement", 448));
        l.add(row(1, 90, "26/06/2026", "TERMS TEXT", "", "8,888.00", "Dr", "0.00", "Cr"));
        return l;
    }

    private static void addCashbackBox(List<Line> l, int page) {
        l.add(ln(page, 643, "CASHBACK", 266, "DETAILS", 316));
        l.add(ln(page, 657, "Cashback", 123, "Earned", 170, "Cashback", 397, "Credited", 444));
        l.add(ln(page, 669, "30.00", 150, "10.00", 430));
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    private static Line at(List<Line> lines, int page, double top) {
        return lines.stream().filter(x -> x.page() == page && x.top() == top).findFirst().orElseThrow();
    }

    @Test
    void theProfileMatchesTheAxisLayoutOfThisStatement() {
        assertEquals(PROFILE.id(), ONLY_THIS.match(statement(true), "axis").id());
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(true), "axis").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        assertNull(ONLY_THIS.match(statement(true), "hdfc"));
    }

    @Test
    void theWholeWordGenerationOfTheCashbackTableIsTheDtdace2024Layout() {
        assertNull(ONLY_THIS.match(statement(false), "axis"));
        assertEquals(AxisCardDtdace2024.ID_A, LayoutRegistry.defaults().match(statement(false), "axis").id());
    }

    @Test
    void theTableWithoutACashbackColumnIsTheDtda2022Layout() {
        List<Line> lines = statement(true);
        lines.set(lines.indexOf(at(lines, 0, 320)), ln(0, 320, "DATE", 48, "TRANSACTION", 132, "DETAILS", 187,
                "MERCHANT", 289, "CATEGORY", 330, "AMOUNT", 416, "(Rs.)", 452));
        assertNull(ONLY_THIS.match(lines, "axis"));
        assertEquals(AxisCardDtda2022.ID, LayoutRegistry.defaults().match(lines, "axis").id());
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromTheAmountMarker() {
        List<RowResult> r = read(statement(true)).results();
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
    void theCashbackFigureAndItsMarkerAreIgnoredWhateverTheirSign() {
        ReadOutcome o = read(statement(true));
        assertEquals(0, o.rowsDropped());
        assertEquals(1000.00, o.results().get(1).amount, 0.001); // cashback beside it is marked Dr
        assertEquals(-2000.00, o.results().get(0).amount, 0.001); // cashback beside it is marked Cr
        assertTrue(o.results().stream().noneMatch(x -> Math.abs(x.amount) == 20.00 || Math.abs(x.amount) == 10.00));
    }

    @Test
    void everyRowCarriesTheCardLast4FromTheCardNoLine() {
        assertTrue(read(statement(true)).results().stream().allMatch(x -> "1234".equals(x.cardLast4)));
    }

    @Test
    void theEndOfStatementLineEndsTheTableAndTheCashbackBoxAddsNoRows() {
        ReadOutcome o = read(statement(true));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER")));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("TERMS")));
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void aCashbackBoxWithoutAnEndLineIsNotRead() {
        List<Line> lines = statement(true);
        lines.remove(at(lines, 0, 387));
        lines.remove(at(lines, 0, 399));
        lines.removeIf(x -> x.page() == 1);
        ReadOutcome o = read(lines);
        assertEquals(4, o.results().size());
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void aTableThatRunsOnAfterTheCashbackBoxOfItsFirstPageKeepsReading() {
        List<Line> l = summaryBox(true);
        l.add(header(0, 320));
        l.add(ln(0, 331, "Card", 39, "No:", 59, "451457******1234", 118));
        l.add(row(0, 342, "21/06/2026", "UPI TESTSHOP,CITY", "GROCERY", "2,000.00", "Dr", "20.00", "Cr"));
        l.add(row(0, 353, "22/06/2026", "BBPS PAYMENT RECEIVED", "", "1,000.00", "Cr", "0.00", "Dr"));
        addCashbackBox(l, 0);
        l.add(ln(1, 60, "Axis", 288, "Bank", 316, "Credit", 376, "Card", 417, "Statement", 448));
        l.add(header(1, 82));
        l.add(row(1, 92, "23/06/2026", "MERCHANT ONE", "TRAVEL", "1,000.00", "Dr", "10.00", "Cr"));
        l.add(row(1, 103, "24/06/2026", "ANNUAL FEE", "", "50.00", "Dr", "0.00", "Cr"));
        l.add(ln(1, 115, "****", 251, "End", 272, "of", 289, "Statement", 300, "****", 344));
        ReadOutcome o = read(l);
        assertEquals(4, o.results().size());
        assertEquals(LocalDate.of(2026, 6, 23), o.results().get(2).date);
        assertEquals("1234", o.results().get(3).cardLast4);
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void periodAccountNumberAndBankAreFilled() {
        ReadOutcome o = read(statement(true));
        assertEquals(LocalDate.of(2026, 6, 20), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 7, 18), o.meta().periodEnd);
        assertEquals("451457******1234", o.meta().accountNumber);
        assertEquals("Axis Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theSummaryBoxGivesDueDatesAndAmountsWithCrDrApplied() {
        ReadOutcome o = read(statement(true));
        assertEquals(LocalDate.of(2026, 8, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(305.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
    }

    @Test
    void aCrMarkedTotalDueIsNegativeAndACrMarkedMinimumDueIsZero() {
        List<Line> lines = statement(true);
        lines.set(lines.indexOf(at(lines, 0, 238)), ln(0, 238, "3,050.00", 59, "Cr", 99, "305.00", 170, "Cr", 204,
                "20/06/2026", 254, "-", 299, "18/07/2026", 305, "07/08/2026", 390, "18/07/2026", 507));
        ReadOutcome o = read(lines);
        assertEquals(-3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @Test
    void theLimitsBoxReadsTheLetterspacedLabelsIntoTheThreeLimits() {
        ReadOutcome o = read(statement(true));
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theLimitLabelsAlsoReadWhenPrintedWholeOrWithOtherBreaks() {
        List<Line> lines = statement(true);
        lines.set(lines.indexOf(at(lines, 0, 253)), ln(0, 253, "Credit", 43, "Card", 70, "Number", 91, "Credit", 167,
                "Limit", 194, "Available", 257, "Credit", 296, "Limit", 323, "Available", 372, "Cash", 411,
                "Limit", 432));
        ReadOutcome o = read(lines);
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theEquationRowGivesPreviousBalancePaymentsCreditsPurchasesCashAndCharges() {
        ReadOutcome o = read(statement(true));
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("credits_received"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("cash_advance"), 0.001);
        assertEquals(50.00, (Double) o.summaryFields().get("fees_and_charges"), 0.001);
    }

    @Test
    void thisGenerationPrintsNoRewardFinanceChargeOrPointsEarnedFigure() {
        ReadOutcome o = read(statement(true));
        assertFalse(o.summaryFields().containsKey("reward_points_balance"));
        assertFalse(o.summaryFields().containsKey("finance_charges"));
        assertFalse(o.summaryFields().containsKey("reward_points_earned"));
        assertTrue(PROFILE.notPrinted().containsAll(
                List.of("reward_points_balance", "finance_charges", "reward_points_earned")));
    }

    @Test
    void openingAndClosingReconcileSoTheRowsGetARunningBalance() {
        ReadOutcome o = read(statement(true));
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(3000.00, o.results().get(0).balance, 0.001);
        assertEquals(3050.00, o.results().get(3).balance, 0.001);
        assertEquals(1000.00, o.meta().openingBalance, 0.001);
        assertEquals(3050.00, o.meta().closingBalance, 0.001);
    }

    @Test
    void aPageShiftedLeftStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement(true)) {
            if (l.top() >= 320 && l.page() == 0) {
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
        List<Line> lines = statement(true);
        lines.set(lines.indexOf(at(lines, 0, 375)), ln(0, 375, "24/06/2026", 34, "ANNUAL FEE", 89, "50.00", 402,
                "0.00", 490));
        List<RowResult> r = read(lines).results();
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertEquals("default", r.get(3).signSource);
    }

    @Test
    void theProfileDefinitionIsTheAxisCardOfTheCardProduct() {
        assertEquals("axis-card-dtdace-2021", PROFILE.id());
        assertEquals("axis", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
