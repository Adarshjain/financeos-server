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
 * The HSBC Premier card layout of 2026 that prints no header or summary labels, on a synthetic statement: coordinates
 * as printed, every value invented. Opening 1,000 - payment 1,000 + purchases 825.50 - refund 300 = 525.50.
 */
class HsbcCardNohdr2026Test {

    private static final LayoutProfile PROFILE = HsbcCardNohdr2026.build();

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
        l.add(ln(0, 72, "MR", 58, "TEST", 74, "HOLDER", 115));
        l.add(ln(0, 93, "XXXXXXXXXXX", 58, "07", 371, "OCT", 380, "2026", 395, "250.00", 492));
        l.add(ln(0, 121, "08", 337, "AUG", 347, "2026", 364, "To", 383, "07", 395, "SEP", 404, "2026", 420, "2,000.00", 500));
        l.add(ln(0, 135, "XXXXXXXXXXX", 58, "HSBC", 378, "PREMIER", 403));
        l.add(ln(0, 176, "State:", 53, "99", 73, "-", 83, "KERALA", 87, "12xx", 364, "xxxx", 380, "xxxx", 396, "7777", 412,
                "12xx", 485, "xxxx", 502, "xxxx", 518, "7777", 534));
        l.add(ln(0, 200, "100,000.00", 84, "50,000.00", 211));
        l.add(ln(0, 275, "Available", 78, "Credit", 108, "Limit", 129, "97,000.00", 374));
        l.add(ln(0, 288, "OPENING", 77, "BALANCE", 111, "1,000.00", 377));
        l.add(ln(0, 296, "01SEP", 30, "BBPS", 77, "PMT", 97, "REF1", 114, "1,000.00", 377, "CR", 413));
        l.add(ln(0, 308, "PURCHASES", 77, "&", 123, "INSTALLMENTS", 130));
        l.add(ln(0, 319, "Interest", 77, "Rate", 102, "applicable", 119, ":", 152, "1.99%", 156, "p.m.", 178));
        l.add(ln(0, 331, "12xx", 76, "xxxx", 93, "xxxx", 109, "7777", 125, "TEST", 144, "HOLDER", 175));
        l.add(ln(0, 340, "02SEP", 30, "TESTSHOP", 77, "ONE", 130, "Delhi", 160, "500.00", 386));
        l.add(ln(0, 362, "03SEP", 30, "SHOP", 77, "TWO", 120, "250.50", 386));
        l.add(ln(0, 669, "1,000.00", 72, "825.50", 210, "1,300.00", 348, "525.50", 486));
        // the table goes on over the page, without a header
        l.add(ln(1, 50, "04SEP", 31, "LATE", 78, "ROW", 100, "75.00", 387));
        l.add(ln(1, 72, "05SEP", 31, "REFUND", 78, "300.00", 387, "CR", 413));
        l.add(ln(1, 100, "TOTAL", 79, "PURCHASE", 103, "OUTSTANDING", 144, "825.50", 380));
        l.add(ln(1, 110, "07SEP", 32, "NET", 79, "OUTSTANDING", 95, "BALANCE", 148, "525.50", 380));
        l.add(ln(1, 120, "08SEP", 31, "AFTER", 78, "END", 120, "9,999.00", 380));
        return l;
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    @Test
    void theProfileMatchesTheHsbcPremierLayoutOfThisStatement() {
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
    void aStatementOfAnotherHsbcCardProductIsAnotherLayout() {
        List<Line> lines = new ArrayList<>(statement());
        lines.set(3, ln(0, 135, "XXXXXXXXXXX", 58, "HSBC", 378, "LIVE+", 403));
        LayoutProfile p = LayoutRegistry.defaults().match(lines, "hsbc");
        assertTrue(p == null || !PROFILE.id().equals(p.id()));
    }

    @Test
    void aStatementThatPrintsTheTableHeaderIsTheOtherHsbcLayout() {
        List<Line> lines = new ArrayList<>(statement());
        lines.add(6, ln(0, 276, "DATE", 34, "TRANSACTION", 167, "DETAILS", 219, "AMOUNTS", 362, "(", 400, ")", 414));
        lines.add(1, ln(0, 53, "Payment", 363, "due", 390, "date", 402, "Minimal", 476, "payment", 500, "due", 526));
        assertEquals("hsbc-card-dtd-2026", LayoutRegistry.defaults().match(lines, "hsbc").id());
    }

    @Test
    void rowsAreReadWithYearlessDatesNarrationsAndSignsFromTheCrMarker() {
        List<RowResult> r = read(statement()).results();
        assertEquals(5, r.size());
        assertEquals(LocalDate.of(2026, 9, 1), r.get(0).date);
        assertEquals("BBPS PMT REF1", r.get(0).description);
        assertEquals(1000.00, r.get(0).amount, 0.001);
        assertEquals("TESTSHOP ONE Delhi", r.get(1).description);
        assertEquals(-500.00, r.get(1).amount, 0.001);
        assertEquals(-250.50, r.get(2).amount, 0.001);
        assertEquals(-75.00, r.get(3).amount, 0.001);
        assertEquals("LATE ROW", r.get(3).description);
        assertEquals(300.00, r.get(4).amount, 0.001);
    }

    @Test
    void theOpeningBalanceLineIsTheAnchorSoThePaymentRowAboveThePurchasesHeadingIsARow() {
        RowResult first = read(statement()).results().get(0);
        assertEquals(LocalDate.of(2026, 9, 1), first.date);
    }

    @Test
    void theSectionHeadingTheInterestNoteAndTheCardHolderLineAreNoNarration() {
        List<RowResult> r = read(statement()).results();
        assertTrue(r.stream().noneMatch(x -> x.description.contains("INSTALLMENTS")
                || x.description.contains("Interest") || x.description.contains("HOLDER")));
    }

    @Test
    void theTotalPurchaseOutstandingLineEndsTheTableForGood() {
        ReadOutcome o = read(statement());
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER") || x.description.contains("NET")));
    }

    @Test
    void everyRowCarriesTheLast4OfTheCard() {
        assertTrue(read(statement()).results().stream().allMatch(x -> "7777".equals(x.cardLast4)));
    }

    @Test
    void periodAccountNumberAndBankComeFromTheUnlabelledLines() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 8, 8), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 9, 7), o.meta().periodEnd);
        assertEquals("12xx xxxx xxxx 7777", o.meta().accountNumber);
        assertEquals("HSBC", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theDueDateMinimumAndTotalAreReadFromTheTwoUnlabelledLines() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 10, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(250.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(2000.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void theAvailableLimitAndTheAccountSummaryRowAreRead() {
        ReadOutcome o = read(statement());
        assertEquals(97000.00, (Double) o.summaryFields().get("available_credit_limit"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(825.50, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(1000.00, o.meta().openingBalance.doubleValue(), 0.001);
        assertEquals(525.50, o.meta().closingBalance.doubleValue(), 0.001);
    }

    @Test
    void theProfileDefinitionIsTheHsbcPremierCardOfTheCardProduct() {
        assertEquals("hsbc-card-nohdr-2026", PROFILE.id());
        assertEquals("hsbc", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
