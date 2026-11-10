package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Each summary value position and type, the Cr / minus handling of dues, page filters and meta targets. */
class ProfiledReaderSummaryTest {

    /** A table of one row, preceded by the given summary lines; the profile is the single-amount layout plus the summary. */
    private static ReadOutcome read(UnaryOperator<LayoutProfile.Builder> summary, Line... summaryLines) {
        List<Line> lines = new ArrayList<>(List.of(summaryLines));
        lines.add(singleHeader(0, 300, 0));
        lines.add(singleRow(0, 310, 0, "05/01/2026", "SHOP", "100.00", "Dr"));
        LayoutProfile.Builder b = single("sum").suffix("Cr", "Dr", Direction.DEBIT);
        return ProfiledReader.read(lines, summary.apply(b).build()).outcome();
    }

    // ---- RIGHT_ON_LINE ----

    @Test
    void rightOnLineAmountIsTheFirstFigureAfterTheLabel() {
        ReadOutcome o = read(b -> b.summary("credit_limit", "credit limit").rightOnLine().done(),
                ln(0, 50, "Credit", 40, "Limit", 80, "50,000.00", 200));
        assertEquals(50000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void rightOnLineDoesNotLookAtFiguresLeftOfTheLabel() {
        ReadOutcome o = read(b -> b.summary("credit_limit", "credit limit").rightOnLine().done(),
                ln(0, 50, "9,999.00", 10, "Credit", 80, "Limit", 120));
        assertNull(o.summaryFields().get("credit_limit"));
    }

    @Test
    void rightOnLineDate() {
        ReadOutcome o = read(b -> b.summary("payment_due_date", "due date").type(ValueType.DATE).rightOnLine().done(),
                ln(0, 50, "Due", 40, "Date", 70, "07/02/2026", 120));
        assertEquals(LocalDate.of(2026, 2, 7), o.summaryFields().get("payment_due_date"));
    }

    @Test
    void rightOnLineInteger() {
        ReadOutcome o = read(b -> b.summary("reward_points_balance", "reward points").type(ValueType.INTEGER)
                        .rightOnLine().done(),
                ln(0, 50, "Reward", 40, "Points", 80, "12,450", 130));
        assertEquals(12450.0, ((Number) o.summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
    }

    // ---- BELOW_BY_X ----

    private static Line[] box() {
        return new Line[]{
                ln(0, 50, "Total", 52, "Due", 85, "Minimum", 152, "Due", 200),
                ln(0, 62, "700.00", 60, "70.00", 170)};
    }

    @Test
    void belowByXPicksTheFigureUnderTheLabel() {
        ReadOutcome o = read(b -> b.summary("total_amount_due", "total due").done()
                .summary("minimum_amount_due", "minimum due").done(), box());
        assertEquals(700.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(70.00, (Double) o.summaryFields().get("minimum_amount_due"), 0.001);
    }

    @Test
    void belowByXSkipsLinesWithNothingUnderTheLabelUpToMaxLines() {
        Line[] lines = {
                ln(0, 50, "Credit", 40, "Limit", 80),
                ln(0, 56, "some", 300, "text", 340),
                ln(0, 62, "5,000.00", 45)};
        ReadOutcome near = read(b -> b.summary("credit_limit", "credit limit").maxLines(2).done(), lines);
        ReadOutcome far = read(b -> b.summary("credit_limit", "credit limit").maxLines(1).done(), lines);
        assertEquals(5000.00, (Double) near.summaryFields().get("credit_limit"), 0.001);
        assertNull(far.summaryFields().get("credit_limit"));
    }

    @Test
    void belowByXIgnoresFiguresOutsideTheLabelRange() {
        ReadOutcome o = read(b -> b.summary("credit_limit", "credit limit").done(),
                ln(0, 50, "Credit", 40, "Limit", 80),
                ln(0, 62, "5,000.00", 300));
        assertNull(o.summaryFields().get("credit_limit"));
    }

    @Test
    void belowByXToleranceWidensTheLabelRange() {
        Line[] lines = {ln(0, 50, "Credit", 40, "Limit", 80), ln(0, 62, "5,000.00", 140)};
        assertNull(read(b -> b.summary("credit_limit", "credit limit").xTolerance(5).done(), lines)
                .summaryFields().get("credit_limit"));
        assertEquals(5000.00, (Double) read(b -> b.summary("credit_limit", "credit limit").xTolerance(40).done(), lines)
                .summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void belowByXNeverReadsAcrossAPageBreak() {
        ReadOutcome o = read(b -> b.summary("credit_limit", "credit limit").done(),
                ln(0, 50, "Credit", 40, "Limit", 80),
                ln(1, 10, "5,000.00", 45));
        assertNull(o.summaryFields().get("credit_limit"));
    }

    @Test
    void belowByXDate() {
        ReadOutcome o = read(b -> b.summary("payment_due_date", "due date").type(ValueType.DATE).done(),
                ln(0, 50, "Payment", 300, "Due", 340, "Date", 360),
                ln(0, 62, "07/02/2026", 320));
        assertEquals(LocalDate.of(2026, 2, 7), o.summaryFields().get("payment_due_date"));
    }

    @Test
    void belowByXDateRangeFillsThePeriod() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.PERIOD, "statement period").type(ValueType.DATE_RANGE).done(),
                ln(0, 50, "Statement", 270, "Period", 310),
                ln(0, 62, "20/12/2025", 262, "-", 330, "19/01/2026", 340));
        assertEquals(LocalDate.of(2025, 12, 20), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 1, 19), o.meta().periodEnd);
    }

    @Test
    void aDateRangeWithOnlyOneDateGivesNoPeriod() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.PERIOD, "statement period").type(ValueType.DATE_RANGE).done(),
                ln(0, 50, "Statement", 270, "Period", 310),
                ln(0, 62, "20/12/2025", 262));
        assertNull(o.meta().periodStart);
        assertNull(o.meta().periodEnd);
    }

    @Test
    void belowByXMaskedNumberFillsTheAccountNumber() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "card number").type(ValueType.MASKED_NUMBER).done(),
                ln(0, 50, "Card", 50, "Number", 80),
                ln(0, 62, "123456******7890", 50));
        assertEquals("123456******7890", o.meta().accountNumber);
    }

    @Test
    void aPlainNumberIsNotAMaskedNumber() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "card number").type(ValueType.MASKED_NUMBER).done(),
                ln(0, 50, "Card", 50, "Number", 80),
                ln(0, 62, "1234567890", 50));
        assertNull(o.meta().accountNumber);
    }

    @Test
    void openingClosingAndStatementDateMetaTargets() {
        ReadOutcome o = read(b -> b
                        .summary(MetaTarget.OPENING_BALANCE, "opening").rightOnLine().done()
                        .summary(MetaTarget.CLOSING_BALANCE, "closing").rightOnLine().done()
                        .summary(MetaTarget.STATEMENT_DATE, "statement date").type(ValueType.DATE).rightOnLine().done(),
                ln(0, 40, "Opening", 40, "100.00", 120),
                ln(0, 50, "Closing", 40, "250.00", 120),
                ln(0, 60, "Statement", 40, "Date", 90, "31/01/2026", 130));
        assertEquals(100.00, o.meta().openingBalance, 0.001);
        assertEquals(250.00, o.meta().closingBalance, 0.001);
        assertEquals(LocalDate.of(2026, 1, 31), o.meta().documentDate);
    }

    // ---- NTH_AMOUNT_BELOW ----

    private static Line[] equation() {
        return new Line[]{
                ln(0, 50, "Previous", 40, "Balance", 90, "-", 130, "Payments", 140, "=Total", 200, "Due", 240),
                ln(0, 56, "some", 300, "wrapped", 340, "text", 380),
                ln(0, 62, "1,000.00", 40, "Dr", 100, "400.00", 150, "1,200.00", 220)};
    }

    @Test
    void nthAmountBelowTakesTheNthFigureOfTheNextLineWithFigures() {
        ReadOutcome o = read(b -> b
                .summary("previous_balance", "previous balance").nthAmountBelow(1).done()
                .summary("payments_received", "payments").nthAmountBelow(2).done()
                .summary("total_amount_due", "=total").nthAmountBelow(3).done(), equation());
        assertEquals(1000.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(400.00, (Double) o.summaryFields().get("payments_received"), 0.001);
        assertEquals(1200.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void nthAmountBelowWithTooFewFiguresGivesNoValue() {
        ReadOutcome o = read(b -> b.summary("total_amount_due", "=total").nthAmountBelow(4).done(), equation());
        assertNull(o.summaryFields().get("total_amount_due"));
    }

    // ---- credit marking ----

    @Test
    void creditIsNegativeMakesACrMarkedDueNegativeAndALeavesDrPositive() {
        Line[] lines = {
                ln(0, 50, "Total", 52, "Due", 85, "Previous", 152, "Balance", 200),
                ln(0, 62, "700.00", 60, "Cr", 100, "70.00", 170, "Dr", 210)};
        ReadOutcome o = read(b -> b.summary("total_amount_due", "total due").creditIsNegative().done()
                .summary("previous_balance", "previous balance").creditIsNegative().done(), lines);
        assertEquals(-700.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(70.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
    }

    @Test
    void creditIsNegativeAlsoAppliesToAGluedSuffixAndToAMinus() {
        Line[] lines = {
                ln(0, 50, "Total", 52, "Due", 85, "Previous", 152, "Balance", 200),
                ln(0, 62, "700.00Cr", 60, "-70.00", 170)};
        ReadOutcome o = read(b -> b.summary("total_amount_due", "total due").creditIsNegative().done()
                .summary("previous_balance", "previous balance").creditIsNegative().done(), lines);
        assertEquals(-700.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(-70.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
    }

    @Test
    void withoutCreditIsNegativeACrMarkedFigureStaysPositive() {
        ReadOutcome o = read(b -> b.summary("total_amount_due", "total due").done(),
                ln(0, 50, "Total", 52, "Due", 85),
                ln(0, 62, "700.00", 60, "Cr", 100));
        assertEquals(700.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    @Test
    void creditIsZeroTurnsACrMarkedFigureIntoZeroAndKeepsADrOne() {
        Line[] lines = {
                ln(0, 50, "Minimum", 52, "Due", 95, "Other", 152, "Due", 200),
                ln(0, 62, "70.00", 60, "Cr", 100, "80.00", 170, "Dr", 210)};
        ReadOutcome o = read(b -> b.summary("minimum_amount_due", "minimum due").creditIsZero().done()
                .summary("fees_and_charges", "other due").creditIsZero().done(), lines);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0);
        assertEquals(80.00, (Double) o.summaryFields().get("fees_and_charges"), 0.001);
    }

    // ---- selection ----

    @Test
    void theFirstLineThatYieldsAValueWins() {
        ReadOutcome o = read(b -> b.summary("credit_limit", "credit limit").rightOnLine().done(),
                ln(0, 40, "Credit", 40, "Limit", 80, "applies", 120),
                ln(0, 50, "Credit", 40, "Limit", 80, "1,000.00", 140),
                ln(0, 60, "Credit", 40, "Limit", 80, "2,000.00", 140));
        assertEquals(1000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void pagesLimitWhereALabelIsSearched() {
        Line first = ln(0, 50, "Credit", 40, "Limit", 80, "1,000.00", 140);
        Line second = ln(1, 50, "Credit", 40, "Limit", 80, "2,000.00", 140);
        List<Line> lines = new ArrayList<>(List.of(first, singleHeader(0, 300, 0),
                singleRow(0, 310, 0, "05/01/2026", "SHOP", "100.00", "Dr"), second));
        LayoutProfile onSecond = single("p").suffix("Cr", "Dr", Direction.DEBIT).continuesWithoutHeader(false)
                .summary("credit_limit", "credit limit").rightOnLine().pages(1).done().build();
        LayoutProfile anyPage = single("p").suffix("Cr", "Dr", Direction.DEBIT).continuesWithoutHeader(false)
                .summary("credit_limit", "credit limit").rightOnLine().done().build();
        assertEquals(2000.00, (Double) ProfiledReader.read(lines, onSecond).outcome().summaryFields().get("credit_limit"), 0.001);
        assertEquals(1000.00, (Double) ProfiledReader.read(lines, anyPage).outcome().summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void tableLinesAreNeverSearchedForSummaryLabels() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "CREDIT LIMIT", "100.00", "Dr"));
        LayoutProfile p = single("t").suffix("Cr", "Dr", Direction.DEBIT)
                .summary("credit_limit", "credit limit").rightOnLine().done().build();
        assertNull(ProfiledReader.read(lines, p).outcome().summaryFields().get("credit_limit"));
    }

    @Test
    void labelRegexIsCaseInsensitive() {
        ReadOutcome o = read(b -> b.summary("credit_limit", "credit limit").rightOnLine().done(),
                ln(0, 50, "CREDIT", 40, "LIMIT", 90, "1,000.00", 140));
        assertEquals(1000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
    }
}
