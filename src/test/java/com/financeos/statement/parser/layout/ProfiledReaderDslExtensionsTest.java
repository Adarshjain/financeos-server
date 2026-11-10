package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.layout.LayoutProfile.EndScope;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Single-date periods, regex capture, two-line headers, IGNORE columns and FOR_GOOD end markers above a sub-table. */
class ProfiledReaderDslExtensionsTest {

    @AfterEach
    void reset() {
        LayoutRouting.reset();
    }

    private static List<Line> table() {
        return List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
    }

    private static ReadOutcome read(UnaryOperator<LayoutProfile.Builder> summary, Line... top) {
        List<Line> lines = new ArrayList<>(List.of(top));
        lines.addAll(table());
        return ProfiledReader.read(lines, summary.apply(bank("ext")).build()).outcome();
    }

    // ---- gap 1: single-date period ----

    @Test
    void periodEndTakesASingleDateAndLeavesThePeriodStartEmpty() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.PERIOD_END, "statement date").type(ValueType.DATE).rightOnLine().done(),
                ln(0, 50, "Statement", 40, "Date", 100, "31/01/2026", 150));
        assertEquals(LocalDate.of(2026, 1, 31), o.meta().periodEnd);
        assertNull(o.meta().periodStart);
    }

    @Test
    void periodStartAndPeriodEndCanBeDeclaredSeparately() {
        ReadOutcome o = read(b -> b
                        .summary(MetaTarget.PERIOD_START, "from").type(ValueType.DATE).rightOnLine().done()
                        .summary(MetaTarget.PERIOD_END, "until").type(ValueType.DATE).rightOnLine().done(),
                ln(0, 50, "from", 40, "01/01/2026", 100), ln(0, 60, "until", 40, "31/01/2026", 100));
        assertEquals(LocalDate.of(2026, 1, 1), o.meta().periodStart);
        assertEquals(LocalDate.of(2026, 1, 31), o.meta().periodEnd);
    }

    @Test
    void yearlessRowDatesResolveFromAPeriodEndAlone() {
        LayoutProfile p = bank("yl").dateFormats("dd/MM/yyyy", "dd MMM")
                .summary(MetaTarget.PERIOD_END, "statement date").type(ValueType.DATE).rightOnLine().done().build();
        List<Line> lines = List.of(
                ln(0, 50, "Statement", 40, "Date", 100, "15/01/2026", 150),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "28 Dec", "LATE DEC", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "03 Jan", "EARLY JAN", "10.00", null, "80.00"));
        List<RowResult> r = ProfiledReader.read(lines, p).outcome().results();
        assertEquals(LocalDate.of(2025, 12, 28), r.get(0).date);
        assertEquals(LocalDate.of(2026, 1, 3), r.get(1).date);
    }

    @Test
    void periodStartAndEndRequireADateType() {
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary(MetaTarget.PERIOD_END, "x").type(ValueType.AMOUNT).done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary(MetaTarget.PERIOD_START, "x").done());
    }

    @Test
    void aProfileStatementDateSurvivesFinishWhileV1KeepsTheDocumentFactsDate() {
        List<Line> lines = new ArrayList<>(List.of(
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                ln(0, 45, "Statement", 40, "Date", 100, "15/01/2026", 150),
                ln(0, 50, "Opening", 40, "Balance:", 100, "100.00", 160),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "50.00", "120.00")));
        LayoutProfile p = bank("sd")
                .summary(MetaTarget.STATEMENT_DATE, "statement date").type(ValueType.DATE).rightOnLine().done().build();
        LayoutRouting.setRegistry(new LayoutRegistry(List.of(p)));
        LayoutRouting.setForcedReader("layout");
        ParsedStatement layout = StatementParseEngine.parseLines(lines, false, null);
        LayoutRouting.setForcedReader("v1");
        ParsedStatement v1 = StatementParseEngine.parseLines(lines, false, null);
        assertEquals(LocalDate.of(2026, 1, 15), layout.meta().documentDate);
        assertEquals(LocalDate.of(2026, 1, 31), v1.meta().documentDate);
    }

    // ---- gap 2: capture ----

    @Test
    void captureReadsADateGluedToItsLabel() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.PERIOD_END, "^statement").type(ValueType.DATE)
                        .capture("date:\\s*(\\S+)").done(),
                ln(0, 50, "Statement", 253, "Date:15/01/2026", 300));
        assertEquals(LocalDate.of(2026, 1, 15), o.meta().periodEnd);
    }

    @Test
    void captureReadsAMaskedNumberPrintedAsSeveralWordsKeepingInnerSpaces() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "card no").type(ValueType.MASKED_NUMBER)
                        .capture("card no\\s+(.+)$").done(),
                ln(0, 50, "Card", 360, "No", 388, "4514", 402, "XXXX", 421, "XX", 442, "3536", 464));
        assertEquals("4514 XXXX XX 3536", o.meta().accountNumber);
    }

    @Test
    void compactRemovesTheWhitespaceInsideAMaskedCapture() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "card no").type(ValueType.MASKED_NUMBER)
                        .capture("card no\\s+(.+)$").compact().done(),
                ln(0, 50, "Card", 360, "No", 388, "4514", 402, "XXXX", 421, "XX", 442, "3536", 464));
        assertEquals("4514XXXXXX3536", o.meta().accountNumber);
    }

    @Test
    void captureAmountHandlesACrSuffixWithTheDuesFlags() {
        Line due = ln(0, 50, "Total", 40, "Due", 80, "1,234.00", 120, "Cr", 170);
        ReadOutcome neg = read(b -> b.summary("total_amount_due", "total due").capture("due\\s+(.+)$")
                .creditIsNegative().done(), due);
        ReadOutcome plain = read(b -> b.summary("total_amount_due", "total due").capture("due\\s+(.+)$").done(), due);
        ReadOutcome zero = read(b -> b.summary("minimum_amount_due", "total due").capture("due\\s+(.+)$")
                .creditIsZero().done(), due);
        assertEquals(-1234.00, (Double) neg.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(1234.00, (Double) plain.summaryFields().get("total_amount_due"), 0.001);
        assertEquals(0.0, (Double) zero.summaryFields().get("minimum_amount_due"), 0.0);
    }

    @Test
    void captureAmountWithoutASuffix() {
        ReadOutcome o = read(b -> b.summary("credit_limit", "credit limit").capture("limit\\s+(\\S+)").done(),
                ln(0, 50, "Credit", 40, "Limit", 80, "5,000.00", 120));
        assertEquals(5000.00, (Double) o.summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void captureInteger() {
        ReadOutcome o = read(b -> b.summary("reward_points_balance", "points").type(ValueType.INTEGER)
                        .capture("points\\s+(\\S+)").done(),
                ln(0, 50, "Reward", 40, "Points", 80, "12,450", 130));
        assertEquals(12450.0, ((Number) o.summaryFields().get("reward_points_balance")).doubleValue(), 0.001);
    }

    @Test
    void captureDateRangeFromTwoGroupsAndFromTwoDatesInOneGroup() {
        Line range = ln(0, 50, "Period", 40, "01/01/2026", 100, "to", 170, "31/01/2026", 190);
        ReadOutcome twoGroups = read(b -> b.summary(MetaTarget.PERIOD, "period").type(ValueType.DATE_RANGE)
                .capture("period\\s+(\\S+)\\s+to\\s+(\\S+)").done(), range);
        ReadOutcome oneGroup = read(b -> b.summary(MetaTarget.PERIOD, "period").type(ValueType.DATE_RANGE)
                .capture("period\\s+(.+)$").done(), range);
        for (ReadOutcome o : List.of(twoGroups, oneGroup)) {
            assertEquals(LocalDate.of(2026, 1, 1), o.meta().periodStart);
            assertEquals(LocalDate.of(2026, 1, 31), o.meta().periodEnd);
        }
    }

    @Test
    void aCaptureThatDoesNotMatchOrCannotBeParsedGivesNoValue() {
        ReadOutcome nomatch = read(b -> b.summary("credit_limit", "credit limit").capture("zzz\\s+(\\S+)").done(),
                ln(0, 50, "Credit", 40, "Limit", 80, "5,000.00", 120));
        ReadOutcome unparsable = read(b -> b.summary("credit_limit", "credit limit").capture("limit\\s+(\\S+)").done(),
                ln(0, 50, "Credit", 40, "Limit", 80, "n/a", 120));
        ReadOutcome oneDate = read(b -> b.summary(MetaTarget.PERIOD, "period").type(ValueType.DATE_RANGE)
                        .capture("period\\s+(.+)$").done(),
                ln(0, 50, "Period", 40, "01/01/2026", 100));
        assertNull(nomatch.summaryFields().get("credit_limit"));
        assertNull(unparsable.summaryFields().get("credit_limit"));
        assertNull(oneDate.meta().periodStart);
    }

    @Test
    void captureLinesAlsoTriesTheFollowingLines() {
        Line[] two = {ln(0, 50, "Credit", 40, "Limit", 80), ln(0, 60, "Amount", 40, "5,000.00", 100)};
        ReadOutcome without = read(b -> b.summary("credit_limit", "credit limit").capture("amount\\s+(\\S+)").done(), two);
        ReadOutcome with = read(b -> b.summary("credit_limit", "credit limit").capture("amount\\s+(\\S+)")
                .captureLines(1).done(), two);
        assertNull(without.summaryFields().get("credit_limit"));
        assertEquals(5000.00, (Double) with.summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void aCaptureNeedsAGroup() {
        assertThrows(IllegalArgumentException.class, () -> bank("x").summary("credit_limit", "x").capture("no group"));
    }

    // ---- headers over two lines ----

    private static LayoutProfile twoLineHeader(int lines) {
        return bank("two").headerLines(lines).build();
    }

    private static List<Line> twoLineTable(double dx2) {
        return List.of(
                ln(0, 100, "Date", DATE, "Narration", NARR),
                ln(0, 108, "Debit", DEBIT + dx2, "Credit", CREDIT + dx2, "Balance", BAL + dx2),
                bankRow(0, 118, dx2, "05/01/2026", "SHOP", "10.00", null, "90.00"));
    }

    @Test
    void aHeaderSpanningTwoLinesIsFoundAndTheTableStartsAfterTheLastHeaderLine() {
        List<Line> lines = twoLineTable(0);
        assertTrue(ProfiledReader.hasHeader(lines, twoLineHeader(2)));
        List<RowResult> r = ProfiledReader.read(lines, twoLineHeader(2)).outcome().results();
        assertEquals(1, r.size());
        assertEquals(-10.00, r.get(0).amount, 0.001);
    }

    @Test
    void eachLabelAnchorsItsColumnByItsOwnX0OnItsOwnLine() {
        List<Line> lines = twoLineTable(20);
        List<Line> shifted = new ArrayList<>(lines);
        shifted.set(2, bankRow(0, 118, 20, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        var res = ProfiledReader.read(shifted, twoLineHeader(2));
        // the date stays at the first line's x; debit / balance follow the second line's labels
        assertEquals(1, res.outcome().results().size());
        assertEquals("SHOP", res.outcome().results().get(0).description);
        assertEquals(90.00, res.outcome().results().get(0).balance, 0.001);
    }

    @Test
    void aTwoLineHeaderIsNotMatchedByDefault() {
        assertFalse(ProfiledReader.hasHeader(twoLineTable(0), twoLineHeader(1)));
    }

    @Test
    void theFirstLabelMustSitOnTheFirstLineOfTheWindow() {
        List<Line> lines = List.of(
                ln(0, 90, "Cover", 50),
                ln(0, 100, "Date", DATE, "Narration", NARR),
                ln(0, 108, "Debit", DEBIT, "Credit", CREDIT, "Balance", BAL),
                bankRow(0, 118, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        List<RowResult> r = ProfiledReader.read(lines, twoLineHeader(3)).outcome().results();
        assertEquals(1, r.size());
    }

    @Test
    void aHeaderWindowNeverSpansAPageBreak() {
        List<Line> lines = List.of(
                ln(0, 100, "Date", DATE, "Narration", NARR),
                ln(1, 20, "Debit", DEBIT, "Credit", CREDIT, "Balance", BAL));
        assertFalse(ProfiledReader.hasHeader(lines, twoLineHeader(2)));
    }

    @Test
    void bothHeaderLinesAreHeaderNotRowsOrUnread() {
        var res = ProfiledReader.read(twoLineTable(0), twoLineHeader(2));
        assertEquals(0, res.unreadAmountLines());
    }

    // ---- IGNORE column ----

    private static LayoutProfile withIgnore() {
        return bank("ign").absoluteColumn(Role.IGNORE, 560, 700).build();
    }

    @Test
    void anIgnoreColumnSwallowsDatesTimesAmountsAndSpacedMinusSignsWithoutUnread() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "SHOP", 120, "10.00", 330, "90.00", 470,
                        "-", 565, "999.00", 575, "10:30", 620, "07/01/2026", 650));
        var res = ProfiledReader.read(lines, withIgnore());
        assertEquals(1, res.outcome().results().size());
        assertEquals(0, res.unreadAmountLines());
        assertEquals("SHOP", res.outcome().results().get(0).description);
    }

    @Test
    void anAmountOnlyInsideAnIgnoreColumnOfANonRowLineIsNotUnread() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"),
                ln(0, 120, "-", 565, "1,000.00", 580));
        assertEquals(0, ProfiledReader.read(lines, withIgnore()).unreadAmountLines());
        // the same figure with no IGNORE column over it is an unread amount line
        assertEquals(1, ProfiledReader.read(lines, bank("noign").build()).unreadAmountLines());
    }

    // ---- end marker above a sub-table ----

    @Test
    void aForGoodEndMarkerOnASummaryHeadingKeepsTheSubTableBelowItOutOfTheRows() {
        LayoutProfile p = bank("sub").endMarker("reward\\s+summary", EndScope.FOR_GOOD).build();
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"),
                ln(0, 130, "Reward", 50, "Summary", 100),
                bankHeader(0, 140, 0),
                bankRow(0, 150, 0, "06/01/2026", "POINTS ROW", "5.00", null, "85.00"));
        var res = ProfiledReader.read(lines, p);
        assertEquals(1, res.outcome().results().size());
        assertEquals(0, res.unreadAmountLines());
    }
}
