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
 * The HDFC card layout without a REWARDS column (2025) on a synthetic statement: coordinates as printed, every value
 * invented. Previous dues 1,000 - payments 1,000 + purchases 3,000 + finance charges 50 = total due 3,050. The table
 * starts on page 0 and continues on page 1, which prints no header and has its own geometry.
 */
class HdfcCardDttda2025Test {

    private static final LayoutProfile PROFILE = HdfcCardDttda2025.build();

    private static Line ln(int page, double top, Object... textAndX0) {
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < textAndX0.length; i += 2) {
            String t = (String) textAndX0[i];
            double x0 = ((Number) textAndX0[i + 1]).doubleValue();
            words.add(new Word(t, x0, x0 + 5.2 * t.length(), top, page));
        }
        return new Line(words);
    }

    /** The statement; rewardsColumn adds the REWARDS column of the other 2025 generation to the table header. */
    private static List<Line> statement(boolean rewardsColumn) {
        List<Line> l = new ArrayList<>();
        l.add(ln(0, 63, "Test", 26, "Card", 73, "HDFC", 110, "Bank", 140, "Credit", 167, "Card", 200, "Statement", 226));
        l.add(ln(0, 98, "TEST", 26, "USER", 63, "Credit", 197, "Card", 216, "No.", 231, "400000XXXXXX1234", 318));
        l.add(ln(0, 123, "Statement", 197, "Date", 229, "17", 338, "Jun,", 349, "2026", 362));
        l.add(ln(0, 141, "Billing", 197, "Period", 216, "18", 296, "May,", 305, "2026", 321, "-", 337, "17", 340,
                "Jun,", 349, "2026", 362));
        l.add(ln(0, 222, "PAYMENTS/CREDITS", 155, "PURCHASES/DEBIT", 258));
        l.add(ln(0, 226, "PREVIOUS", 39, "STATEMENT", 70, "DUES", 108, "FINANCE", 353, "CHARGES", 380, "TOTAL", 445,
                "AMOUNT", 466, "DUE", 495));
        l.add(ln(0, 230, "RECEIVED", 171, "(Current", 254, "Billing", 280, "Cycle)", 299));
        l.add(ln(0, 245, "_", 132));
        l.add(ln(0, 250, "C1,000.00", 65, "C1,000.00", 169, "+", 232, "C3,000.00", 267, "+", 334, "C50.00", 371, "=", 423,
                "C3,050.00", 445));
        l.add(ln(0, 273, "TOTAL", 64, "CREDIT", 85, "LIMIT", 107));
        l.add(ln(0, 278, "AVAILABLE", 182, "CREDIT", 216, "LIMIT", 238, "AVAILABLE", 317, "CASH", 351, "LIMIT", 368,
                "MINIMUM", 445, "DUE", 478, "DUE", 512, "DATE", 526));
        l.add(ln(0, 282, "(Including", 70, "Cash)", 101));
        l.add(ln(0, 297, "C305.00", 445, "07", 512, "Jul,", 521, "2026", 534));
        l.add(ln(0, 301, "C1,00,000", 77, "C97,000", 202, "C20,000", 335));
        l.add(ln(0, 342, "Past", 23, "Dues", 38, "OVER", 102, "LIMIT", 117, "CURRENT", 423, "DUES", 448,
                "MINIMUM", 504, "DUES", 532));
        l.add(ln(0, 350, "(if", 23, "any)", 30, "C0.00", 102, "C0.00", 182, "C305.00", 423, "C305.00", 504));
        l.add(ln(0, 542, rewardsColumn
                ? new Object[]{"DATE", 170, "&", 187, "TIME", 193, "TRANSACTION", 250, "DESCRIPTION", 294,
                        "REWARDS", 463, "AMOUNT", 528, "PI", 567}
                : new Object[]{"DATE", 170, "&", 187, "TIME", 193, "TRANSACTION", 260, "DESCRIPTION", 304,
                        "AMOUNT", 528, "PI", 567}));
        l.add(ln(0, 556, "TEST", 260, "USER", 286, "[CKYC", 328));
        l.add(ln(0, 571, "21/06/2026|", 170, "14:32", 208, "UPI-SHOPONE", 260, "C", 535, "2,000.00", 540, "l", 567));
        l.add(ln(0, 585, "22/06/2026|", 170, "10:05", 208, "EMI", 242, "UPI-SHOPTWO", 260, "C", 535, "1,000.00", 540,
                "l", 567));
        l.add(ln(0, 835, "Page", 26, "1", 40, "of", 44, "2", 50));
        // page 1: no header, the table continues in its own geometry
        l.add(ln(1, 58, "offers", 22, "on", 45, "your", 56, "card", 74));
        l.add(ln(1, 280, "BPPY", 149, "CC", 165, "PAYMENT", 181, "REF1", 228, "(Ref#", 258));
        l.add(ln(1, 284, "23/06/2026|", 26, "09:00", 64, "+", 514, "C", 520, "1,000.00", 525, "l", 564));
        l.add(ln(1, 288, "ST0001)", 149));
        l.add(ln(1, 299, "24/06/2026|", 26, "08:00", 64, "FINANCE", 149, "CHARGES", 189, "C", 525, "50.00", 530, "l", 564));
        l.add(ln(1, 320, "Cash", 169, "Back", 185, "Summary", 202));
        l.add(ln(1, 326, "SR", 169, "NO.", 178, "TRANSACTION", 207, "AMOUNT", 540));
        l.add(ln(1, 332, "1", 169, "5%", 207, "Shop", 221, "Cashback", 242, "C", 543, "9.00", 548));
        l.add(ln(1, 345, "25/06/2026|", 26, "07:00", 64, "AFTER", 149, "END", 183, "C", 525, "9.00", 530, "l", 564));
        return l;
    }

    private static List<Line> statement() {
        return statement(false);
    }

    private static ReadOutcome read(List<Line> lines) {
        return ProfiledReader.read(lines, PROFILE).outcome();
    }

    private static List<Line> replace(List<Line> lines, int page, double top, Line replacement) {
        List<Line> out = new ArrayList<>(lines);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).page() == page && out.get(i).top() == top) {
                out.set(i, replacement);
            }
        }
        return out;
    }

    private static List<Line> without(List<Line> lines, int page, double top) {
        List<Line> out = new ArrayList<>(lines);
        out.removeIf(x -> x.page() == page && x.top() == top);
        return out;
    }

    @Test
    void theProfileMatchesTheHdfcLayoutWithoutARewardsColumn() {
        assertEquals(PROFILE.id(), LayoutRegistry.defaults().match(statement(), "hdfc").id());
    }

    @Test
    void theProfileDoesNotMatchAnotherIssuer() {
        LayoutProfile p = LayoutRegistry.defaults().match(statement(), "axis");
        assertTrue(p == null || !PROFILE.id().equals(p.id()));
    }

    @Test
    void aTableWithARewardsColumnIsNotThisLayout() {
        LayoutProfile p = LayoutRegistry.defaults().match(statement(true), "hdfc");
        assertTrue(p == null || !PROFILE.id().equals(p.id()));
    }

    @Test
    void rowsAreReadWithDatesNarrationsAndSignsFromThePrefix() {
        List<RowResult> r = read(statement()).results();
        assertEquals(4, r.size());
        assertEquals(LocalDate.of(2026, 6, 21), r.get(0).date);
        assertEquals("UPI-SHOPONE", r.get(0).description);
        assertEquals(-2000.00, r.get(0).amount, 0.001);
        assertEquals(LocalDate.of(2026, 6, 23), r.get(2).date);
        assertEquals(1000.00, r.get(2).amount, 0.001);
        assertEquals(-50.00, r.get(3).amount, 0.001);
        assertEquals("prefix", r.get(2).signSource);
    }

    @Test
    void anEmiMarkerBeforeTheDescriptionIsPartOfTheNarration() {
        RowResult r = read(statement()).results().get(1);
        assertEquals(LocalDate.of(2026, 6, 22), r.date);
        assertEquals("EMI UPI-SHOPTWO", r.description);
        assertEquals(-1000.00, r.amount, 0.001);
    }

    @Test
    void theNameLineUnderTheHeaderIsNotNarrationOfTheFirstRow() {
        assertEquals("UPI-SHOPONE", read(statement()).results().get(0).description);
    }

    @Test
    void theContinuationPageReadsInItsOwnGeometryAndJoinsWrappedNarrationAboveAndBelow() {
        List<RowResult> r = read(statement()).results();
        assertEquals("BPPY CC PAYMENT REF1 (Ref# ST0001)", r.get(2).description);
        assertEquals("FINANCE CHARGES", r.get(3).description);
    }

    @Test
    void theCashBackSummaryHeadingEndsTheTableForGood() {
        ReadOutcome o = read(statement());
        assertTrue(o.results().stream().noneMatch(x -> x.description.contains("AFTER")));
        assertEquals(0, o.rowsDropped());
    }

    @Test
    void everyRowCarriesTheLast4OfTheCardNumber() {
        assertTrue(read(statement()).results().stream().allMatch(x -> "1234".equals(x.cardLast4)));
    }

    @Test
    void periodStatementDateAccountNumberAndBankAreFilled() {
        ReadOutcome o = read(statement());
        assertEquals(LocalDate.of(2026, 5, 18), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 6, 17), o.meta().periodEnd);
        assertEquals(LocalDate.of(2026, 6, 17), o.meta().documentDate);
        assertEquals("400000XXXXXX1234", o.meta().accountNumber);
        assertEquals("HDFC Bank", o.meta().bank);
        assertTrue(o.isCreditCard());
    }

    @Test
    void theEquationRowGivesPreviousDuesPaymentsPurchasesFinanceChargesAndTotal() {
        ReadOutcome o = read(statement());
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(50.00, (Double) o.summaryFields().get("finance_charges"), 0.001);
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void aPreviousDuesFigureNoAmountGrammarReadsLeavesTheOtherFiguresInPlace() {
        List<Line> lines = replace(statement(), 0, 250, ln(0, 250, "C--250.00", 65, "C1,000.00", 169, "+", 232,
                "C3,000.00", 267, "+", 334, "C50.00", 371, "=", 423, "C3,050.00", 445));
        ReadOutcome o = read(lines);
        assertFalse(o.summaryFields().containsKey("previous_balance"));
        assertEquals(1000.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(3000.00, (Double) o.summaryFields().get("total_purchases"), 0.001);
        assertEquals(50.00, (Double) o.summaryFields().get("finance_charges"), 0.001);
        assertEquals(3050.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void aSignedTotalDueIsNegative() {
        List<Line> lines = replace(statement(), 0, 250, ln(0, 250, "C1,000.00", 65, "C1,000.00", 169, "+", 232,
                "C0.00", 267, "+", 334, "C0.00", 371, "=", 423, "C-250.00", 445));
        assertEquals(-250.00, (Double) read(lines).summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void theWholeRupeeLimitsAreReadFromTheirRow() {
        ReadOutcome o = read(statement());
        assertEquals(100000.0, ((Number) o.summaryFields().get("credit_limit")).doubleValue(), 0.001);
        assertEquals(97000.0, ((Number) o.summaryFields().get("available_credit_limit")).doubleValue(), 0.001);
        assertEquals(20000.0, ((Number) o.summaryFields().get("available_cash_limit")).doubleValue(), 0.001);
    }

    @Test
    void theMinimumDueDueDateAndOverLimitAreRead() {
        ReadOutcome o = read(statement());
        assertEquals(305.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
        assertEquals(LocalDate.of(2026, 7, 7), o.summaryFields().get("payment_due_date"));
        assertEquals(0.0, (Double) o.summaryFields().get("overlimit_amount"), 0.001);
    }

    @Test
    void aNilDueDateIsAStatedNoneNotADate() {
        List<Line> lines = replace(statement(), 0, 297, ln(0, 297, "C0.00", 445, "Nil", 512));
        ReadOutcome o = read(lines);
        assertFalse(o.summaryFields().containsKey("payment_due_date"));
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @Test
    void thisGenerationHasNoRewardPointsFiguresAndNoFeesLine() {
        ReadOutcome o = read(statement());
        assertFalse(o.summaryFields().containsKey("reward_points_balance"));
        assertFalse(o.summaryFields().containsKey("reward_points_earned"));
        assertFalse(o.summaryFields().containsKey("fees_and_charges"));
    }

    @Test
    void openingAndClosingReconcileSoTheRowsGetARunningBalance() {
        ReadOutcome o = read(statement());
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(3000.00, o.results().get(0).balance, 0.001);
        assertEquals(3050.00, o.results().get(3).balance, 0.001);
    }

    @Test
    void aRowWithoutAPlusIsADebitReadByDefault() {
        List<Line> lines = replace(statement(), 1, 284, ln(1, 284, "23/06/2026|", 25, "09:00", 64, "C", 522,
                "1,000.00", 527, "l", 565));
        RowResult r = read(lines).results().get(2);
        assertEquals(-1000.00, r.amount, 0.001);
        assertEquals("default", r.signSource);
    }

    @Test
    void theProfileDefinitionIsTheHdfcCardOfTheCardProduct() {
        assertEquals("hdfc-card-dttda-2025", PROFILE.id());
        assertEquals("hdfc", PROFILE.issuerId());
        assertEquals(LayoutProfile.Product.CARD, PROFILE.product());
    }
}
