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
 * The HSBC card layout of 2026 that prints a table header, on a synthetic statement: coordinates as printed, every value
 * invented. Opening 1,000 - payment 1,000 + purchases 750.50 - refund 300 = 450.50.
 */
class HsbcCardDtd2026Test {

    private static final LayoutProfile PROFILE = HsbcCardDtd2026.build();

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
        l.add(ln(0, 33, "PAYMENT", 413, "SUMMARY", 449));
        l.add(ln(0, 53, "Payment", 363, "due", 390, "date", 402, "Minimal", 476, "payment", 500, "due", 526, "(", 539, ")", 552));
        l.add(ln(0, 63, "07", 370, "OCT", 380, "2026", 396, "250.00", 505));
        l.add(ln(0, 83, "Statement", 364, "period", 395, "Total", 480, "payment", 496, "due", 523, "(", 535, ")", 549));
        l.add(ln(0, 93, "08", 337, "AUG", 346, "2026", 364, "To", 383, "07", 395, "SEP", 405, "2026", 420, "2,000.00", 502));
        l.add(ln(0, 114, "HSBC", 378, "LIVE+", 403, "CREDIT", 428, "CARD", 460));
        l.add(ln(0, 178, "Primary", 358, "card", 382, "number", 397, "Account", 491, "number", 517));
        l.add(ln(0, 183, "State:", 53, "99", 73, "-", 83, "KERALA", 87));
        l.add(ln(0, 189, "12xx", 364, "xxxx", 380, "xxxx", 396, "7777", 412, "12xx", 485, "xxxx", 502, "xxxx", 518, "7777", 534));
        l.add(ln(0, 201, "Credit", 73, "limit", 92, "(", 106, ")", 120, "Cash", 203, "limit", 220, "(", 234, ")*", 247));
        l.add(ln(0, 213, "100,000.00", 84, "50,000.00", 211));
        l.add(ln(0, 276, "DATE", 34, "TRANSACTION", 167, "DETAILS", 219, "AMOUNTS", 362, "(", 400, ")", 414));
        l.add(ln(0, 290, "Available", 78, "Credit", 108, "Limit", 129, "97,000.00", 374));
        l.add(ln(0, 302, "OPENING", 78, "BALANCE", 112, "1,000.00", 381));
        l.add(ln(0, 310, "01SEP", 31, "BBPS", 78, "PMT", 98, "REF1", 115, "1,000.00", 381, "CR", 414));
        l.add(ln(0, 334, "PURCHASES", 78, "&", 123, "INSTALLMENTS", 130));
        l.add(ln(0, 345, "Interest", 78, "Rate", 103, "applicable", 120, ":", 153, "1.99%", 157, "p.m.", 179));
        l.add(ln(0, 357, "12xx", 77, "xxxx", 94, "xxxx", 110, "7777", 126, "TEST", 145, "HOLDER", 176));
        l.add(ln(0, 388, "02SEP", 31, "TESTSHOP", 78, "ONE", 130, "Delhi", 160, "500.00", 387));
        l.add(ln(0, 409, "03SEP", 31, "SHOP", 78, "TWO", 120, "250.50", 387));
        l.add(ln(0, 431, "04SEP", 31, "REFUND", 78, "300.00", 387, "CR", 414));
        l.add(ln(0, 460, "TOTAL", 79, "PURCHASE", 103, "OUTSTANDING", 144, "750.50", 380));
        l.add(ln(0, 470, "07SEP", 32, "NET", 79, "OUTSTANDING", 95, "BALANCE", 148, "450.50", 380));
        l.add(ln(0, 480, "08SEP", 31, "AFTER", 78, "END", 120, "9,999.00", 380));
        l.add(ln(0, 657, "ACCOUNT", 258, "SUMMARY", 295));
        l.add(ln(0, 676, "Opening", 55, "balance", 81, "(", 105, ")", 117, "Purchase", 180, "&", 208, "other", 214, "charges", 231,
                "(", 256, ")", 268, "Payment", 319, "&", 346, "other", 352, "credits", 369, "(", 390, ")", 402,
                "Net", 455, "Outstanding", 467, "balance", 504, "(", 528, ")", 540));
        l.add(ln(0, 687, "1,000.00", 73, "750.50", 210, "1,300.00", 348, "450.50", 488));
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
    void theProfileMatchesTheHsbcLayoutOfThisStatement() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(), "hsbc").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        LayoutProfile hdfc = LayoutRegistry.defaults().match(statement(), "hdfc");
        LayoutProfile yes = LayoutRegistry.defaults().match(statement(), "yes");
        assertTrue(hdfc == null || !PROFILE.id().equals(hdfc.id()));
        assertTrue(yes == null || !PROFILE.id().equals(yes.id()));
    }

    @Test
    void aStatementWithoutThePaymentSummaryWordingIsAnotherLayout() {
        List<Line> lines = new ArrayList<>(statement());
        lines.set(1, ln(0, 53, "Payment", 363, "due", 390, "date", 402, "Minimum", 476, "payment", 500, "due", 526));
        LayoutProfile p = LayoutRegistry.defaults().match(lines, "hsbc");
        assertTrue(p == null || !PROFILE.id().equals(p.id()));
    }

    @Test
    void aStatementWithoutTheTableHeaderIsAnotherLayout() {
        LayoutProfile p = LayoutRegistry.defaults().match(without(statement(), 276), "hsbc");
        assertTrue(p == null || !PROFILE.id().equals(p.id()));
    }

    @Test
    void rowsAreReadWithYearlessDatesNarrationsAndSignsFromTheCrMarker() {
        List<RowResult> r = read(statement()).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 9, 1), r.get(0).date);
        assertEquals("BBPS PMT REF1", r.get(0).description);
        assertEquals(1000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 9, 2), r.get(1).date);
        assertEquals("TESTSHOP ONE Delhi", r.get(1).description);
        assertEquals(-500.00, r.get(1).amount, 0.001);
        assertEquals("SHOP TWO", r.get(2).description);
        assertEquals(-250.50, r.get(2).amount, 0.001);
        assertEquals(300.00, r.get(3).amount, 0.001);
        assertEquals("default", r.get(1).signSource);
        assertEquals("suffix", r.get(0).signSource);
    }

    @Test
    void theTotalPurchaseOutstandingLineEndsTheTableForGood() {
        ReadOutcome o = read(statement());
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER") || x.description.contains("NET")));
    }

    @Test
    void theCardHolderLineAndTheInterestRateNoteAreNoNarration() {
        List<RowResult> r = read(statement()).results();
        assertTrue(r.stream().noneMatch(x -> x.description.contains("HOLDER") || x.description.contains("Interest")));
    }

    @Test
    void everyRowCarriesTheLast4OfTheCard() {
        assertTrue(read(statement()).results().stream().allMatch(x -> "7777".equals(x.cardLast4)));
    }

    @Test
    void periodAccountNumberAndBankAreFilled() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 8, 8), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 9, 7), o.meta().periodEnd);
        assertEquals("12xx xxxx xxxx 7777", o.meta().accountNumber);
        assertEquals("HSBC", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void thePaymentSummaryGivesDueDateMinimumAndTotal() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 10, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(250.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(2000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void theLimitsAreReadUnderTheirOwnLabels() {
        ReadOutcome o = read(statement());
        assertEquals(100000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
        assertEquals(50000.00, (Double) o.summaryFields().get("cash_limit"), 0.001);
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
    }

    @Test
    void theOpeningAndClosingBalancesAndThePurchasesAreRead() {
        ReadOutcome o = read(statement());
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(750.50, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(1000.00, o.meta().openingBalance.doubleValue(), 0.001);
        assertEquals(450.50, o.meta().closingBalance.doubleValue(), 0.001);
    }

    @Test
    void theAccountSummaryRowUnderTheTableIsNotAnUnreadAmountLine() {
        List<Line> lines = new ArrayList<>(statement());
        // the table goes on over the page: the account summary sits under the rows, before the end marker
        lines.removeIf(x -> x.top() == 460 || x.top() == 470 || x.top() == 480);
        lines.add(ln(1, 460, "TOTAL", 79, "PURCHASE", 103, "OUTSTANDING", 144, "750.50", 380));
        assertEquals(0, ProfiledReader.read(lines, PROFILE).unreadAmountLines());
    }

    @Test
    void aPageShiftedRightStillReadsBecauseColumnsFollowTheHeader() {
        List<Line> shifted = new ArrayList<>();
        for (Line l : statement()) {
            if (l.top() >= 276 && l.top() < 460) {
                List<Word> ws = new ArrayList<>();
                for (Word w : l.words()) {
                    ws.add(new Word(w.text(), w.x0() + 4, w.x1() + 4, w.top(), w.page()));
                }
                shifted.add(new Line(ws));
            } else {
                shifted.add(l);
            }
        }
        List<RowResult> r = read(shifted).results();
        assertEquals(4, r.size());
        assertEquals(1000.00, r.get(0).amount, 0.001);
    }

    @Test
    void theProfileDefinitionIsTheHsbcCardOfTheCardProduct() {
        assertEquals("hsbc-card-dtd-2026", PROFILE.id());
        assertEquals("hsbc", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
