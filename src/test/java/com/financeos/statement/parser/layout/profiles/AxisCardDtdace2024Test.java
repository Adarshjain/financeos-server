package com.financeos.statement.parser.layout.profiles;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.Word;
import com.financeos.statement.parser.layout.LayoutProfile;
import com.financeos.statement.parser.layout.LayoutRegistry;
import com.financeos.statement.parser.layout.ProfiledReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Axis card layout of 2024 with the CASHBACK EARNED column, in its two x-arrangements, on a synthetic statement:
 * coordinates as printed, every value invented. Previous balance 1,000 Dr - payments 1,000 - credits 0 + purchases 3,000
 * + cash 0 + other 50 = total due 3,050 Dr. Each row also carries a cashback figure with its own Cr / Dr marker.
 */
class AxisCardDtdace2024Test {

    /** A: AMOUNT label at x 418, amounts left-aligned at 402; B: AMOUNT label at x 451, amounts right-aligned to 505. */
    enum V {
        A("axis-card-dtdace-2024a", 48, 136, 185, 292, 330, 418, 452, 505, 542, 266, false, 490, 506),
        B("axis-card-dtdace-2024b", 48, 173, 222, 346, 384, 451, 485, 522, 559, 339, true, 556, 576);

        final String id;
        final double date, trans, details, merchant, category, amount, rs, cashback, earned, catX, cbX, cbMarkerX;
        final boolean rightAligned;

        V(String id, double date, double trans, double details, double merchant, double category, double amount,
          double rs, double cashback, double earned, double catX, boolean rightAligned, double cbX, double cbMarkerX) {
            this.id = id;
            this.date = date;
            this.trans = trans;
            this.details = details;
            this.merchant = merchant;
            this.category = category;
            this.amount = amount;
            this.rs = rs;
            this.cashback = cashback;
            this.earned = earned;
            this.catX = catX;
            this.rightAligned = rightAligned;
            this.cbX = cbX;
            this.cbMarkerX = cbMarkerX;
        }

        LayoutProfile profile() {
            return this == A ? AxisCardDtdace2024.buildA() : AxisCardDtdace2024.buildB();
        }

        /** x0 of an amount word of this text length: left-aligned at 402, or right-aligned so it ends at 505. */
        double amountX(String t) {
            return rightAligned ? 505 - 5.2 * t.length() : 402;
        }

        double markerX(String t) {
            return rightAligned ? 507 : amountX(t) + 5.2 * t.length() + 2;
        }
    }

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static Line header(V v, int page, double top) {
        return ln(page, top, "DATE", v.date, "TRANSACTION", v.trans, "DETAILS", v.details, "MERCHANT", v.merchant,
                "CATEGORY", v.category, "AMOUNT", v.amount, "(Rs.)", v.rs, "CASHBACK", v.cashback, "EARNED", v.earned);
    }

    /** A row: amount with marker, then the cashback figure with its own marker. */
    private static Line row(V v, int page, double top, String date, String narr, String cat, String amt, String mk,
                            String cb, String cbMk) {
        List<Word> words = new ArrayList<>(ln(page, top, date, 34, narr, 89).words());
        if (!cat.isEmpty()) {
            words.addAll(ln(page, top, cat, v.catX).words());
        }
        words.addAll(ln(page, top, amt, v.amountX(amt), mk, v.markerX(amt), cb, v.cbX, cbMk, v.cbMarkerX).words());
        return new Line(words);
    }

    private static List<Line> summaryBox() {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 60, "Axis", 293, "Bank", 320, "Credit", 377, "Card", 416, "Statement", 446));
        l.add(ln(0, 210, "PAYMENT", 252, "SUMMARY", 297));
        l.add(ln(0, 224, "Total", 52, "Payment", 70, "Due", 101, "Minimum", 152, "Payment", 185, "Due", 216,
                "Statement", 272, "Period", 308, "Payment", 381, "Due", 412, "Date", 427,
                "Statement", 472, "Generation", 509, "Date", 548));
        l.add(ln(0, 238, "3,050.00", 62, "Dr", 96, "305.00", 173, "Dr", 201, "20/06/2026", 260, "-", 299,
                "18/07/2026", 303, "07/08/2026", 393, "18/07/2026", 510));
        l.add(ln(0, 253, "Credit", 51, "Card", 72, "Number", 89, "Credit", 172, "Limit", 194, "Available", 265,
                "Credit", 298, "Limit", 319, "Available", 379, "Cash", 411, "Limit", 429));
        l.add(ln(0, 267, "451457******1234", 51, "100,000.00", 173, "97,000.00", 283, "20,000.00", 396));
        l.add(ln(0, 282, "Previous", 69, "Balance", 99, "-", 126, "Payments", 130, "-", 164, "Credits", 168, "+", 193,
                "Purchase", 199, "+", 230, "Cash", 236, "Advance", 253, "+", 283, "Other", 289, "Debit&Charges", 310,
                "=Total", 361, "Payment", 383, "Due", 414));
        l.add(ln(0, 295, "1,000.00", 46, "Dr", 82, "1,000.00", 114, "0.00", 168, "3,000.00", 211, "0.00", 273,
                "50.00", 343, "3,050.00", 406, "Dr", 440));
        l.add(ln(0, 307, "Account", 272, "Summary", 307));
        return l;
    }

    private static List<Line> statement(V v) {
        List<Line> l = summaryBox();
        l.add(header(v, 0, 321));
        l.add(ln(0, 331, "Card", 36, "No:", 53, "451457******1234", 118, "Name", 264, "TEST", 301, "USER", 334));
        l.add(row(v, 0, 342, "21/06/2026", "UPI TESTSHOP,CITY", "GROCERY", "2,000.00", "Dr", "20.00", "Cr"));
        l.add(row(v, 0, 353, "22/06/2026", "BBPS PAYMENT RECEIVED", "", "1,000.00", "Cr", "0.00", "Dr"));
        l.add(row(v, 0, 364, "23/06/2026", "MERCHANT ONE", "TRAVEL", "1,000.00", "Dr", "10.00", "Cr"));
        l.add(row(v, 0, 375, "24/06/2026", "ANNUAL FEE", "", "50.00", "Dr", "0.00", "Cr"));
        l.add(ln(0, 387, "****", 256, "End", 275, "of", 291, "Statement", 300, "****", 340));
        l.add(row(v, 0, 399, "25/06/2026", "AFTER THE END", "", "9,999.00", "Dr", "0.00", "Cr"));
        addCashbackBox(l, 0);
        l.add(ln(1, 60, "Axis", 293, "Bank", 320, "Credit", 377, "Card", 416, "Statement", 446));
        l.add(row(v, 1, 90, "26/06/2026", "TERMS TEXT", "", "8,888.00", "Dr", "0.00", "Cr"));
        return l;
    }

    private static void addCashbackBox(List<Line> l, int page) {
        l.add(ln(page, 643, "CASHBACK", 270, "DETAILS", 317));
        l.add(ln(page, 657, "Cashback", 130, "Earned", 170, "Cashback", 406, "Credited", 445));
        l.add(ln(page, 669, "30.00", 150, "10.00", 429));
        l.add(ln(page, 682, "Cashback", 37, "earned", 76, "this", 106, "month", 122, "will", 150, "be", 166));
    }

    private static ReadOutcome read(V v, List<Line> lines) {
        return ProfiledReader.read(lines, v.profile()).outcome();
    }

    private static Line at(List<Line> lines, int page, double top) {
        return lines.stream().filter(x -> x.page() == page && x.top() == top).findFirst().orElseThrow();
    }

    /** The statement without the End line and the box, its table running on page 2 with the header repeated. */
    private static List<Line> twoPageTable(V v) {
        List<Line> l = summaryBox();
        l.add(header(v, 0, 321));
        l.add(ln(0, 331, "Card", 36, "No:", 53, "451457******1234", 118));
        l.add(row(v, 0, 342, "21/06/2026", "UPI TESTSHOP,CITY", "GROCERY", "2,000.00", "Dr", "20.00", "Cr"));
        l.add(row(v, 0, 353, "22/06/2026", "BBPS PAYMENT RECEIVED", "", "1,000.00", "Cr", "0.00", "Dr"));
        addCashbackBox(l, 0); // the box sits at the foot of page 1 although the table goes on
        l.add(ln(1, 60, "Axis", 293, "Bank", 320, "Credit", 377, "Card", 416, "Statement", 446));
        l.add(header(v, 1, 82));
        l.add(row(v, 1, 92, "23/06/2026", "MERCHANT ONE", "TRAVEL", "1,000.00", "Dr", "10.00", "Cr"));
        l.add(row(v, 1, 103, "24/06/2026", "ANNUAL FEE", "", "50.00", "Dr", "0.00", "Cr"));
        l.add(ln(1, 115, "****", 256, "End", 275, "of", 291, "Statement", 300, "****", 340));
        return l;
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void theProfileMatchesItsOwnArrangementOfTheStatement(V v) {
        assertEquals(v.id, new LayoutRegistry(List.of(v.profile())).match(statement(v), "axis").id());
        assertEquals(v.id, LayoutRegistry.defaults().match(statement(v), "axis").id());
    }

    @Test
    void eachArrangementIsAnotherProfilesAndNeverMatchesTheOther() {
        assertNull(new LayoutRegistry(List.of(V.A.profile())).match(statement(V.B), "axis"));
        assertNull(new LayoutRegistry(List.of(V.B.profile())).match(statement(V.A), "axis"));
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void theProfileDoesNotMatchAnotherIssuer(V v) {
        assertNull(new LayoutRegistry(List.of(v.profile())).match(statement(v), "hdfc"));
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void theLetterspacedGenerationOfTheCashbackTableIsNotThisLayout(V v) {
        List<Line> lines = statement(v);
        lines.set(2, ln(0, 224, "Total", 52, "Payment", 70, "Due", 101, "Statement", 272, "Per", 308, "iod", 322));
        assertNull(new LayoutRegistry(List.of(v.profile())).match(lines, "axis"));
        assertEquals(AxisCardDtdace2021.ID, LayoutRegistry.defaults().match(lines, "axis").id());
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void theTableWithoutACashbackColumnIsTheOtherAxisLayout(V v) {
        List<Line> lines = statement(v);
        lines.set(lines.indexOf(at(lines, 0, 321)), ln(0, 321, "DATE", 48, "TRANSACTION", v.trans, "DETAILS", v.details,
                "MERCHANT", v.merchant, "CATEGORY", v.category, "AMOUNT", v.amount, "(Rs.)", v.rs));
        assertNull(new LayoutRegistry(List.of(v.profile())).match(lines, "axis"));
        assertEquals(AxisCardDtda2024.ID, LayoutRegistry.defaults().match(lines, "axis").id());
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void rowsAreReadWithDatesNarrationsAndSignsFromTheAmountMarker(V v) {
        List<RowResult> r = read(v, statement(v)).results();
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

    @ParameterizedTest
    @EnumSource(V.class)
    void theCashbackFigureAndItsMarkerAreIgnoredWhateverTheirSign(V v) {
        ReadOutcome o = read(v, statement(v));
        assertEquals(0, o.rowsDropped());
        // the credit row carries a cashback marked Dr and the debit rows one marked Cr: neither changes the row's sign
        assertEquals(1000.00, o.results().get(1).amount, 0.001);
        assertEquals(-2000.00, o.results().get(0).amount, 0.001);
        assertTrue(o.results().stream().noneMatch(x -> Math.abs(x.amount) == 20.00 || Math.abs(x.amount) == 10.00));
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void everyRowCarriesTheCardLast4FromTheCardNoLine(V v) {
        assertTrue(read(v, statement(v)).results().stream().allMatch(x -> "1234".equals(x.cardLast4)));
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void theEndOfStatementLineEndsTheTableAndTheCashbackBoxAddsNoRows(V v) {
        ReadOutcome o = read(v, statement(v));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER")));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("TERMS")));
        assertEquals(0, o.rowsDropped());
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void aTableThatRunsOnAfterTheCashbackBoxOfItsFirstPageKeepsReading(V v) {
        ReadOutcome o = read(v, twoPageTable(v));
        assertEquals(4, o.results().size());
        assertEquals(LocalDate.of(2026, 6, 23), o.results().get(2).date);
        assertEquals(-50.00, o.results().get(3).amount, 0.001);
        assertEquals("1234", o.results().get(3).cardLast4);
        assertEquals(0, o.rowsDropped());
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void aCashbackBoxWithoutAnEndLineIsNotRead(V v) {
        List<Line> lines = statement(v);
        lines.remove(at(lines, 0, 387));
        lines.remove(at(lines, 0, 399));
        lines.removeIf(x -> x.page() == 1);
        ReadOutcome o = read(v, lines);
        assertEquals(4, o.results().size());
        assertEquals(0, o.rowsDropped());
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void periodAccountNumberAndBankAreFilled(V v) {
        ReadOutcome o = read(v, statement(v));
        assertEquals(LocalDate.of(2026, 6, 20), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 7, 18), o.meta().periodEnd);
        assertEquals("451457******1234", o.meta().accountNumber);
        assertEquals("Axis Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void theSummaryBoxGivesDueDatesLimitsAndTheEquationRow(V v) {
        ReadOutcome o = read(v, statement(v));
        assertEquals(LocalDate.of(2026, 8, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(305.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("credits_received"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("cash_advance"), 0.001);
        assertEquals(50.00, (Double) o.summaryFields().get("fees_and_charges"), 0.001);
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void aCrMarkedTotalDueIsNegativeAndACrMarkedMinimumDueIsZero(V v) {
        List<Line> lines = statement(v);
        lines.set(3, ln(0, 238, "3,050.00", 62, "Cr", 96, "305.00", 173, "Cr", 201, "20/06/2026", 260, "-", 299,
                "18/07/2026", 303, "07/08/2026", 393, "18/07/2026", 510));
        ReadOutcome o = read(v, lines);
        assertEquals(-3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void thisLayoutPrintsNoRewardFinanceChargeOrPointsEarnedFigure(V v) {
        ReadOutcome o = read(v, statement(v));
        assertFalse(o.summaryFields().containsKey("reward_points_balance"));
        assertFalse(o.summaryFields().containsKey("finance_charges"));
        assertFalse(o.summaryFields().containsKey("reward_points_earned"));
        assertTrue(v.profile().notPrinted().containsAll(
                List.of("reward_points_balance", "finance_charges", "reward_points_earned")));
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void openingAndClosingReconcileSoTheRowsGetARunningBalance(V v) {
        ReadOutcome o = read(v, statement(v));
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(3000.00, o.results().get(0).balance, 0.001);
        assertEquals(3050.00, o.results().get(3).balance, 0.001);
        assertEquals(1000.00, o.meta().openingBalance, 0.001);
        assertEquals(3050.00, o.meta().closingBalance, 0.001);
    }

    @ParameterizedTest
    @EnumSource(V.class)
    void aRowWithoutAMarkerIsADebitReadByDefault(V v) {
        List<Line> lines = statement(v);
        lines.set(lines.indexOf(at(lines, 0, 375)), ln(0, 375, "24/06/2026", 34, "ANNUAL FEE", 89,
                "50.00", v.amountX("50.00"), "0.00", v.cbX));
        List<RowResult> r = read(v, lines).results();
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertEquals("default", r.get(3).signSource);
    }

    @Test
    void theProfileDefinitionsAreTheAxisCardOfTheCardProduct() {
        for (V v : V.values()) {
            assertEquals(v.id, v.profile().id());
            assertEquals("axis", v.profile().issuerId());
            assertEquals(LayoutProfile.Product.CARD, v.profile().product());
        }
    }
}
