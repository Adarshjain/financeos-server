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
 * The ICICI card layout of 2023 onwards on a synthetic statement: coordinates as printed, every value invented.
 * Previous balance 1,000 + purchases 3,000 + cash 0 - payments 1,000 = total due 3,000.
 */
class IciciCardSdtdra2023Test {

    private static final LayoutProfile PROFILE = IciciCardSdtdra2023.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    /** The table header line of the first page; with rewardLabel false it is the 2021-22 header without Reward. */
    private static Line header(boolean rewardLabel) {
        return rewardLabel
                ? ln(0, 378, "SPENDS", 89, "OVERVIEW", 110, "Date", 207, "SerNo.", 262, "Transaction", 305, "Details", 339,
                        "Reward", 443, "Intl.#", 485, "Amount", 521, "(in`)", 545)
                : ln(0, 378, "SPENDS", 89, "OVERVIEW", 110, "Date", 207, "SerNo.", 262, "Transaction", 305, "Details", 339,
                        "Intl.#", 485, "Amount", 521, "(in`)", 545);
    }

    private static List<Line> statement(boolean rewardLabel) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 193, "STATEMENT", 79, "DATE", 128, "All", 215, "communications", 228));
        l.add(ln(0, 205, "May", 80, "15,", 103, "2026", 120, "To", 221, "update", 240));
        l.add(ln(0, 223, "PAYMENT", 74, "DUE", 114, "DATE", 133, "To", 221, "update", 240));
        l.add(ln(0, 235, "June", 81, "3,", 106, "2026", 117, "Visit", 439, "the", 459));
        l.add(ln(0, 254, "STATEMENT", 205, "SUMMARY", 266));
        l.add(ln(0, 274, "Total", 81, "Amount", 101, "due", 133, "Previous", 221, "Balance", 250, "Purchases", 305, "/", 340,
                "Charges", 344, "Cash", 403, "Advances", 421, "Payments", 488, "/", 521, "Credits", 525));
        l.add(ln(0, 283, "=", 188, "+", 285, "+", 377, "-", 470));
        l.add(ln(0, 288, "`3,000.00", 86, "`1,000.00", 238, "`3,000.00", 318, "`0.00", 418, "`1,000.00", 507));
        l.add(ln(0, 313, "Minimum", 73, "Amount", 109, "due", 141, "CREDIT", 205, "SUMMARY", 243));
        l.add(ln(0, 328, "`150.00", 95));
        l.add(ln(0, 334, "Credit", 208, "Limit", 229, "(Including", 246, "cash)", 270, "Available", 290, "Credit", 322,
                "(Including", 343, "cash)", 366, "Cash", 411, "Limit", 429, "Available", 495, "Cash", 527));
        l.add(ln(0, 343, "Interest", 72, "will", 94, "be", 105, "charged", 114));
        l.add(ln(0, 350, "total", 75, "amount", 89, "due", 112, "is", 124));
        l.add(ln(0, 354, "`100,000.00", 221, "`97,000.00", 311, "`20,000.00", 408, "`19,000.00", 499));
        l.add(header(rewardLabel));
        l.add(rewardLabel ? ln(0, 383, "Points", 445, "amount", 481) : ln(0, 383, "amount", 481));
        l.add(ln(0, 398, "1234XXXXXXXX5678", 207));
        // row 1 with a chart percentage label in the left margin and reward points
        l.add(ln(0, 411, "20%", 89, "20/04/2026", 207, "1111111111", 254, "SHOP", 305, "ONE", 330, "CITY", 350, "IN", 375,
                "20", 450, "2,000.00", 538));
        // its narration tail, nearer to row 1 than to row 2, with offer text in the left margin
        l.add(ln(0, 418, "www.bank.example/offers", 107, "HTTP://WWW.SHOP.ONE", 305, "IN", 395));
        // row 2: a payment (CR marker), zero points
        l.add(ln(0, 431, "25/04/2026", 207, "2222222222", 254, "PAYMENT", 305, "RECEIVED", 340, "0", 452,
                "1,000.00", 523, "CR", 548));
        // row 3: negative reward points, no marker
        l.add(ln(0, 444, "02/05/2026", 207, "3333333333", 254, "SHOP", 305, "TWO", 330, "-5", 449, "1,000.00", 538));
        l.add(ln(0, 456, "#", 205, "International", 208, "Spends", 244));
        l.add(ln(0, 470, "20/05/2026", 207, "4444444444", 254, "AFTER", 305, "END", 340, "9,999.00", 538));
        l.add(ln(0, 788, "Statement", 39, "period", 88, ":", 121, "April", 126, "15,", 155, "2026", 175, "to", 200,
                "May", 215, "14,", 235, "2026", 255));
        return l;
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    @Test
    void theProfileMatchesTheIciciLayoutWithARewardColumn() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(true), "icici").id());
        assertEquals("icici-card-sdtdra-2023", PROFILE.id());
        assertEquals("icici", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        assertNull(LayoutRegistry.defaults().match(statement(true), "hdfc"));
    }

    @Test
    void theHeaderWithoutARewardColumnIsTheEarlierGenerationNotThisLayout() {
        LayoutProfile m = LayoutRegistry.defaults().match(statement(false), "icici");
        assertNotNull(m);
        assertNotEquals(PROFILE.id(), m.id());
        assertEquals(IciciCardSdtdaa2021.ID, m.id());
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromTheCrMarker() {
        List<RowResult> r = read(statement(true)).results();
        assertEquals(3, r.size());
        assertEquals(LocalDate.of(2026, 4, 20), r.get(0).date);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 4, 25), r.get(1).date);
        assertEquals("PAYMENT RECEIVED", r.get(1).description);
        assertEquals(1000.00, r.get(1).amount, 0.001);
        assertEquals("suffix", r.get(1).signSource);
        assertEquals(LocalDate.of(2026, 5, 2), r.get(2).date);
        assertEquals("SHOP TWO", r.get(2).description);
        assertEquals(-1000.00, r.get(2).amount, 0.001);
        assertEquals("default", r.get(2).signSource);
    }

    @Test
    void theSerialNumberAndTheRewardPointsAreNotAmountsAndNotNarration() {
        ReadOutcome o = read(statement(true));
        assertEquals(0, o.rowsDropped());
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("1111111111")));
        assertTrue(o.results().stream().noneMatch(x -> x.description.matches(".*\\s-?\\d+$")));
    }

    @Test
    void aWrappedNarrationLineJoinsTheNearerRowAboveAndLeftMarginOfferTextIsLeftOut() {
        RowResult first = read(statement(true)).results().get(0);
        assertEquals("SHOP ONE CITY IN HTTP://WWW.SHOP.ONE IN", first.description);
        assertFalse(first.description.contains("offers"));
        assertFalse(first.description.contains("20%"));
    }

    @Test
    void everyRowCarriesTheCardLast4FromTheMaskedNumberUnderTheHeader() {
        assertTrue(read(statement(true)).results().stream().allMatch(x -> "5678".equals(x.cardLast4)));
    }

    @Test
    void theInternationalSpendsFootnoteEndsTheTableForGood() {
        ReadOutcome o = read(statement(true));
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER")));
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void periodStatementDateAndBankAreFilled() {
        ReadOutcome o = read(statement(true));
        assertEquals(LocalDate.of(2026, 4, 15), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 5, 14), o.meta().periodEnd);
        assertEquals(LocalDate.of(2026, 5, 15), o.meta().documentDate);
        assertEquals("ICICI Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theDueDateIsReadFromBelowItsLabelNotFromTheOfferTextBesideIt() {
        assertEquals(LocalDate.of(2026, 6, 3), read(statement(true)).summaryFields().get("payment_due_date"));
    }

    @Test
    void theEquationRowGivesTotalDuePreviousBalancePurchasesCashAdvanceAndPayments() {
        ReadOutcome o = read(statement(true));
        assertEquals(3000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(0.0, (Double) o.summaryFields().get("cash_advance"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
    }

    @Test
    void theMinimumDueBoxAndTheLimitsBoxAreRead() {
        ReadOutcome o = read(statement(true));
        assertEquals(150.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(20000.00, (Double) o.summaryFields().get("cash_limit"), 0.001);
        assertEquals(19000.00, (Double) o.summaryFields().get("available_cash_limit"), 0.001);
    }

    @Test
    void aCrMarkedTotalDueIsNegative() {
        List<Line> lines = statement(true);
        lines.set(7, ln(0, 288, "`3,000.00", 86, "Cr", 140, "`1,000.00", 238, "`3,000.00", 318, "`0.00", 418,
                "`1,000.00", 507));
        assertEquals(-3000.00, (Double) read(lines).summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void figuresThisLayoutDoesNotPrintAreNotInTheSummary() {
        ReadOutcome o = read(statement(true));
        assertFalse(o.summaryFields().containsKey("finance_charges"));
        assertFalse(o.summaryFields().containsKey("fees_and_charges"));
        assertFalse(o.summaryFields().containsKey("reward_points_balance"));
        assertFalse(o.summaryFields().containsKey("reward_points_earned"));
    }

    @Test
    void openingAndClosingReconcileSoTheRowsGetARunningBalance() {
        ReadOutcome o = read(statement(true));
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(3000.00, o.results().get(0).balance, 0.001);
        assertEquals(2000.00, o.results().get(1).balance, 0.001);
        assertEquals(3000.00, o.results().get(2).balance, 0.001);
        assertEquals(1000.00, o.meta().openingBalance, 0.001);
        assertEquals(3000.00, o.meta().closingBalance, 0.001);
    }

    @Test
    void aContinuedPageRepeatsTheHeaderWithoutTheCaptionAndWithTheTableAtTheLeft() {
        // page 0 up to its last row (the footnote and what follows it move to the last page), then the continued page
        List<Line> lines = new ArrayList<>();
        for (Line x : statement(true)) {
            if (x.top() < 456 || x.top() > 700) {
                lines.add(x);
            }
        }
        // the second page prints its own header at other x positions; its "amount" word beside the real label must not
        // be taken for the Amount column
        lines.add(ln(1, 65, "Date", 35, "SerNo.", 122, "Transaction", 180, "Details", 214, "Reward", 384, "Points", 407,
                "Intl.#", 444, "amount", 457, "Amount", 525, "(in`)", 548));
        lines.add(ln(1, 78, "28/04/2026", 35, "5555555555", 114, "SHOP", 180, "THREE", 205, "7", 395, "500.00", 541));
        lines.add(ln(1, 95, "#", 32, "International", 35, "Spends", 72));
        List<RowResult> r = read(lines).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 4, 28), r.get(3).date);
        assertEquals("SHOP THREE", r.get(3).description);
        assertEquals(-500.00, r.get(3).amount, 0.001);
    }

    @Test
    void aPageShiftedRightStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement(true)) {
            if (l.top() >= 378 && l.top() < 460) {
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
        assertEquals(3, r.size());
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(1000.00, r.get(1).amount, 0.001);
    }
}
