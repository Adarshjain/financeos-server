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
 * The YES Bank card layout of 2025 on a synthetic statement: coordinates as printed, every value invented. Previous
 * balance 1,000 Dr + purchases and charges 3,050 Dr - payments 1,000 Cr = total due 3,050.
 */
class YesCardDtda2025Test {

    private static final LayoutProfile PROFILE = YesCardDtda2025.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    private static final String REF = "RT000000000000000000001";

    /** The statement; summaryTitle and the lines can be varied to build look-alikes. */
    private static List<Line> statement(String summaryTitle) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 44, "Credit", 228, "Card", 272, "Statement", 308));
        l.add(ln(0, 228, "Overview", 189, summaryTitle, 423, "Summary", 475));
        l.add(ln(0, 240, "Statement", 73, "for", 124, "YES", 140, "BANK", 163, "Card", 195, "Number", 220,
                "451457XXXXXX1234", 261, "Previous", 400, "Balance", 436, ":", 469));
        l.add(ln(0, 255, "Rs.", 400, "1,000.00", 418, "Dr", 452));
        l.add(ln(0, 262, "Statement", 128, "Period:", 169, "Credit", 325, "Limit:", 350));
        l.add(ln(0, 271, "20/06/2026", 102, "To", 145, "18/07/2026", 156, "Rs.", 316, "100,000.00", 330,
                "Current", 400, "Purchases", 431, "/", 473, "Cash", 478, "Advance", 500));
        l.add(ln(0, 285, "Available", 288, "Credit", 325, "Limit:", 350, "&", 400, "Other", 408, "Charges", 431, ":", 465));
        l.add(ln(0, 291, "Statement", 91, "Date", 132, ":", 151, "18/07/2026", 156, "Rs.", 316, "97,000.00", 330,
                "Rs.", 400, "3,050.00", 418, "Dr", 452));
        l.add(ln(0, 312, "Total", 125, "Amount", 146, "Due:", 178, "Cash", 329, "Limit:", 350, "Points", 400,
                "Earned", 426, ":", 456, "7", 461));
        l.add(ln(0, 321, "Rs.", 146, "3,050.00", 158, "Rs.", 342, "20.00", 355));
        l.add(ln(0, 328, "Payment", 400, "&", 435, "Credits", 443, "Received", 473, ":", 510));
        l.add(ln(0, 337, "Minimum", 109, "Amount", 146, "Due:", 178, "Available", 291, "Cash", 329, "Limit:", 350,
                "Rs.", 400, "1,000.00", 418, "Cr", 452));
        l.add(ln(0, 347, "Rs.", 157, "305.00", 170, "Rs.", 342, "10.00", 355));
        l.add(ln(0, 368, "Payment", 81, "Due", 117, "Date:", 134, "07/08/2026", 164, "YES", 245, "ONLINE", 261));
        l.add(ln(0, 399, "Your", 50, "Reward", 76, "Points", 115, "Summary", 148));
        l.add(ln(0, 427, "Opening", 49, "reward", 84, "points", 113, "Point", 163, "earned", 185, "Bonus", 259,
                "points", 286, "Points", 359, "redeemed", 386, "Closing", 458, "reward", 489, "points", 518));
        l.add(ln(0, 449, "120", 87, "7", 185, "0", 283, "0", 390, "127", 494));
        l.add(ln(0, 636, "Statement", 49, "Details", 100));
        l.add(ln(0, 654, "Date", 48, "Transaction", 93, "Details", 147, "Merchant", 377, "Category", 430,
                "Amount", 491, "(Rs.)", 527));
        l.add(ln(0, 675, "21/06/2026", 48, "UPI_TESTSHOP", 93, "IND", 160, "-", 185, "Ref", 190, "No:", 204, REF, 220,
                "Miscellaneous", 377, "Stores", 430, "2,000.00", 500, "Dr", 539));
        l.add(ln(0, 697, "22/06/2026", 48, "PAYMENT", 93, "RECEIVED", 134, "BBPS", 190, "-", 215, "Ref", 220,
                "No:", 234, "123456789012345678", 250, "1,000.00", 505, "Cr", 539));
        l.add(ln(0, 718, "23/06/2026", 48, "UPI_SHOPTWO", 93, "IND", 160, "-", 185, "Ref", 190, "No:", 204, REF, 220,
                "Retail", 377, "Outlet", 410, "Services", 440, "1,000.00", 505, "Dr", 539));
        l.add(ln(0, 739, "24/06/2026", 48, "ANNUAL", 93, "FEE", 135, "-", 160, "Ref", 165, "No:", 180, REF, 195,
                "Service", 377, "50.00", 516, "Dr", 539));
        l.add(ln(0, 765, "------------------End", 201, "of", 274, "the", 284, "Statement------------------", 299));
        l.add(ln(0, 787, "Terms", 45, "AFTER", 184, "END", 203, "9,999.00", 505, "Dr", 539));
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

    private static List<Line> replace(List<Line> lines, double top, Line replacement) {
        List<Line> out = new ArrayList<>(lines);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).top() == top) {
                out.set(i, replacement);
            }
        }
        return out;
    }

    @Test
    void theProfileMatchesTheYesLayoutOfThisStatement() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement("Statement"), "yes").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        LayoutProfile hdfc = LayoutRegistry.defaults().match(statement("Statement"), "hdfc");
        LayoutProfile axis = LayoutRegistry.defaults().match(statement("Statement"), "axis");
        assertTrue(hdfc == null || !PROFILE.id().equals(hdfc.id()));
        assertTrue(axis == null || !PROFILE.id().equals(axis.id()));
    }

    @Test
    void aYesStatementWithoutTheStatementSummaryBoxIsAnotherLayout() {
        assertNull(LayoutRegistry.defaults().match(without(statement("Statement"), 228), "yes"));
    }

    @Test
    void aTableWithDebitAndCreditColumnsIsNotThisLayout() {
        List<Line> lines = replace(statement("Statement"), 654, ln(0, 654, "Date", 48, "Transaction", 93,
                "Details", 147, "Debit", 400, "Credit", 470));
        assertNull(LayoutRegistry.defaults().match(lines, "yes"));
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromTheCrDrMarker() {
        List<RowResult> r = read(statement("Statement")).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 6, 21), r.get(0).date);
        assertEquals("UPI_TESTSHOP IND - Ref No: " + REF + " Miscellaneous Stores", r.get(0).description);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 6, 22), r.get(1).date);
        assertEquals("PAYMENT RECEIVED BBPS - Ref No: 123456789012345678", r.get(1).description);
        assertEquals(1000.00, r.get(1).amount, 0.001);
        assertEquals(-1000.00, r.get(2).amount, 0.001);
        assertEquals(LocalDate.of(2026, 6, 23), r.get(2).date);
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertEquals("ANNUAL FEE - Ref No: " + REF + " Service", r.get(3).description);
        assertTrue(r.stream().allMatch(x -> "suffix".equals(x.signSource)));
    }

    @Test
    void everyRowCarriesTheLast4OfTheCardNumberInTheOverview() {
        assertTrue(read(statement("Statement")).results().stream().allMatch(x -> "1234".equals(x.cardLast4)));
    }

    @Test
    void theEndOfTheStatementLineEndsTheTableForGood() {
        ReadOutcome o = read(statement("Statement"));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER")));
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void creditAndDebitWordsInsideANarrationAreKeptAsPrinted() {
        List<Line> lines = replace(statement("Statement"), 675, ln(0, 675, "21/06/2026", 48, "UPI_SHOP", 93, "LAU", 150,
                "DR", 175, "IND", 190, "UPI", 210, "CR", 232, "Miscellaneous", 377, "Stores", 430, "2,000.00", 500,
                "Dr", 539));
        RowResult first = read(lines).results().get(0);
        assertEquals("UPI_SHOP LAU DR IND UPI CR Miscellaneous Stores", first.description);
        assertEquals(-2000.00, first.amount, 0.001);
    }

    @Test
    void narrationOnTheLinesAroundTheDatedLineJoinsItsRow() {
        List<Line> lines = statement("Statement");
        lines.add(lines.indexOf(lines.stream().filter(x -> x.top() == 675).findFirst().orElseThrow()),
                ln(0, 667, "UPI", 93, "FUEL", 120, "-", 150));
        lines.add(lines.indexOf(lines.stream().filter(x -> x.top() == 697).findFirst().orElseThrow()),
                ln(0, 684, "SECOND", 93, "LINE", 135));
        List<RowResult> r = read(lines).results();
        assertEquals(4, r.size());
        assertEquals("UPI FUEL - UPI_TESTSHOP IND - Ref No: " + REF + " Miscellaneous Stores SECOND LINE",
                r.get(0).description);
    }

    @Test
    void aLoneStrayGlyphOnALineOfItsOwnIsNotNarration() {
        List<Line> lines = statement("Statement");
        lines.add(lines.indexOf(lines.stream().filter(x -> x.top() == 697).findFirst().orElseThrow()),
                ln(0, 690, "C", 342));
        List<RowResult> r = read(lines).results();
        assertEquals("PAYMENT RECEIVED BBPS - Ref No: 123456789012345678", r.get(1).description);
        assertEquals("UPI_TESTSHOP IND - Ref No: " + REF + " Miscellaneous Stores", r.get(0).description);
    }

    @Test
    void periodStatementDateAccountNumberAndBankAreFilled() {
        ReadOutcome o = read(statement("Statement"));
        assertEquals(LocalDate.of(2026, 6, 20), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 7, 18), o.meta().periodEnd);
        assertEquals(LocalDate.of(2026, 7, 18), o.meta().documentDate);
        assertEquals("451457XXXXXX1234", o.meta().accountNumber);
        assertEquals("Yes Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theOverviewGivesDueAmountsAndTheDueDate() {
        ReadOutcome o = read(statement("Statement"));
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(305.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(LocalDate.of(2026, 8, 7), o.summaryFields().get("payment_due_date"));
    }

    @Test
    void theStackedLimitsAreReadUnderTheirOwnLabels() {
        ReadOutcome o = read(statement("Statement"));
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20.00, (Double) o.summaryFields().get("cash_limit"), 0.001);
        assertEquals(10.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void theRightColumnGivesPreviousBalancePurchasesAndPayments() {
        ReadOutcome o = read(statement("Statement"));
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(3050.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertFalse(o.summaryFields().containsKey("finance_charges"));
        assertFalse(o.summaryFields().containsKey("fees_and_charges"));
    }

    @Test
    void thePointsEarnedAndTheClosingRewardPointsAreRead() {
        ReadOutcome o = read(statement("Statement"));
        assertEquals(7.0, ((Number) o.summaryFields().get("reward_points_earned")).doubleValue(), 0.001);
        assertEquals(127.0, ((Number) o.summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
    }

    @Test
    void aWrappedPointsHeaderDoesNotTakeAnExpiryDayCountForTheClosingBalance() {
        List<Line> lines = statement("Statement");
        lines = replace(lines, 427, ln(0, 422, "Opening", 58, "Closing", 362, "reward", 394, "Points", 426,
                "expiring", 452, "Points", 489, "expiring", 516));
        lines = new ArrayList<>(lines);
        int at = lines.indexOf(lines.stream().filter(x -> x.top() == 422).findFirst().orElseThrow());
        lines.add(at + 1, ln(0, 431, "reward", 48, "points", 77, "points", 379, "within", 427, "30", 462, "days", 475));
        lines = replace(lines, 449, ln(0, 449, "120", 69, "7", 131, "0", 193, "0", 259, "127", 386, "0", 452));
        assertEquals(127.0, ((Number) read(lines).summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
    }

    @Test
    void aStatementWithNoRewardBlockHasNoRewardBalance() {
        List<Line> lines = without(without(without(statement("Statement"), 399), 427), 449);
        ReadOutcome o = read(lines);
        assertFalse(o.summaryFields().containsKey("reward_points_balance"));
    }

    @Test
    void aCreditBalanceDueIsNegative() {
        List<Line> lines = replace(statement("Statement"), 321, ln(0, 321, "Rs.", 146, "500.00", 158, "Cr", 200,
                "Rs.", 342, "20.00", 355));
        assertEquals(-500.00, (Double) read(lines).summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void noPaymentRequiredInPlaceOfADueDateLeavesTheDueDateOut() {
        List<Line> lines = replace(statement("Statement"), 368, ln(0, 368, "Payment", 81, "Due", 117, "Date:", 134,
                "NO", 164, "PYMT", 180, "REQD", 210, "YES", 245, "ONLINE", 261));
        ReadOutcome o = read(lines);
        assertFalse(o.summaryFields().containsKey("payment_due_date"));
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void aMinimumDueWithNoFigureUnderItIsLeftOut() {
        ReadOutcome o = read(without(statement("Statement"), 347));
        assertFalse(o.summaryFields().containsKey("minimum_amount_due"));
    }

    @Test
    void openingAndClosingReconcileSoTheRowsGetARunningBalance() {
        ReadOutcome o = read(statement("Statement"));
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(3000.00, o.results().get(0).balance, 0.001);
        assertEquals(2000.00, o.results().get(1).balance, 0.001);
        assertEquals(3050.00, o.results().get(3).balance, 0.001);
    }

    @Test
    void aPageShiftedRightStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement("Statement")) {
            if (l.top() >= 654 && l.top() < 765) {
                List<Word> ws = new ArrayList<>();
                for (Word w : l.words()) {
                    ws.add(new Word(w.text(), w.x0() + 8, w.x1() + 8, w.top(), w.page()));
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
        List<Line> lines = replace(statement("Statement"), 739, ln(0, 739, "24/06/2026", 48, "ANNUAL", 93, "FEE", 135,
                "Service", 377, "50.00", 516));
        RowResult r = read(lines).results().get(3);
        assertEquals(-50.00, r.amount, 0.001);
        assertEquals("default", r.signSource);
    }

    @Test
    void theProfileDefinitionIsTheYesCardOfTheCardProduct() {
        assertEquals("yes-card-dtda-2025", PROFILE.id());
        assertEquals("yes", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
