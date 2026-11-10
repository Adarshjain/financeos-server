package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** TEXT values, IFSC / holder name, an unlabelled card number, last 4 for every row source, zero in the unused column. */
class ProfiledReaderTextTest {

    @AfterEach
    void reset() {
        LayoutRouting.reset();
    }

    private static ReadOutcome read(UnaryOperator<LayoutProfile.Builder> b, Line... top) {
        List<Line> lines = new ArrayList<>(List.of(top));
        lines.add(bankHeader(0, 100, 0));
        lines.add(bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        return ProfiledReader.read(lines, b.apply(bank("txt")).build()).outcome();
    }

    // ---- 1: TEXT ----

    @Test
    void textRightOnLineTakesTheNextWordAndSkipsALoneColon() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "account number").type(ValueType.TEXT)
                        .rightOnLine().done(),
                ln(0, 50, "Account", 40, "Number", 90, ":", 140, "50100123456789", 150));
        assertEquals("50100123456789", o.meta().accountNumber);
    }

    @Test
    void maxWordsTakesSeveralWords() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.HOLDER_NAME, "holder").type(ValueType.TEXT).rightOnLine()
                        .maxWords(2).done(),
                ln(0, 50, "Holder", 40, ":", 90, "TEST", 100, "USER", 140, "EXTRA", 180));
        assertEquals("TEST USER", o.meta().holderName);
    }

    @Test
    void textWithCaptureTakesGroupOne() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "account").type(ValueType.TEXT)
                        .capture("account number\\s*:\\s*(\\d+)").done(),
                ln(0, 50, "Account", 40, "Number", 90, ":", 140, "50100123456789", 150));
        assertEquals("50100123456789", o.meta().accountNumber);
    }

    @Test
    void textBelowByX() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "account no").type(ValueType.TEXT).done(),
                ln(0, 50, "Account", 40, "No", 90), ln(0, 60, "50100123456789", 50));
        assertEquals("50100123456789", o.meta().accountNumber);
    }

    @Test
    void textWithNothingToTheRightGivesNoValue() {
        ReadOutcome o = read(b -> b.summary(MetaTarget.ACCOUNT_NUMBER, "account number").type(ValueType.TEXT)
                .rightOnLine().done(), ln(0, 50, "Account", 40, "Number", 90, ":", 140));
        assertNull(o.meta().accountNumber);
    }

    @Test
    void targetAndTypeCombinationsTheReaderCannotStoreDoNotBuild() {
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary(MetaTarget.ACCOUNT_NUMBER, "x").type(ValueType.INTEGER).done());
        assertThrows(IllegalStateException.class, () -> bank("x").summary(MetaTarget.ACCOUNT_NUMBER, "x").done());
        assertThrows(IllegalStateException.class, () -> bank("x").summary(MetaTarget.IFSC, "x").done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary(MetaTarget.HOLDER_NAME, "x").type(ValueType.MASKED_NUMBER).done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary(MetaTarget.PERIOD, "x").type(ValueType.TEXT).done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary(MetaTarget.OPENING_BALANCE, "x").type(ValueType.TEXT).done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary("credit_limit", "x").type(ValueType.TEXT).done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary("credit_limit", "x").type(ValueType.DATE_RANGE).done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary("payment_due_date", "x").done());
        assertThrows(IllegalStateException.class,
                () -> bank("x").summary("credit_limit", "x").type(ValueType.TEXT).nthAmountBelow(1).done());
        assertDoesNotThrow(() -> bank("x").summary(MetaTarget.ACCOUNT_NUMBER, "x").type(ValueType.TEXT).done());
    }

    // ---- 2: IFSC and holder name, like for like with the generic reader ----

    private static List<Line> withIdentity() {
        return List.of(
                ln(0, 30, "Customer", 40, "Name", 100, ":", 130, "TEST", 140, "USER", 175),
                ln(0, 35, "IFSC", 40, "Code", 70, ":", 100, "HDFC0001234", 110),
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                ln(0, 50, "Opening", 40, "Balance:", 100, "100.00", 160),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "50.00", "120.00"));
    }

    @Test
    void ifscAndHolderNameCapturedWithTheGenericPatternsEqualWhatV1Fills() {
        LayoutProfile p = bank("id")
                .summary(MetaTarget.IFSC, "ifsc").type(ValueType.TEXT).capture("(?-i:\\b([A-Z]{4}0[A-Z0-9]{6})\\b)").done()
                .summary(MetaTarget.HOLDER_NAME, "customer name").type(ValueType.TEXT)
                .capture("(?:customer name|account holder|a/c holder|holder name|name)\\s*[:\\-]\\s*(.+)").done().build();
        LayoutRouting.setRegistry(new LayoutRegistry(List.of(p)));
        LayoutRouting.setForcedReader("v1");
        ParsedStatement v1 = StatementParseEngine.parseLines(withIdentity(), false, null);
        LayoutRouting.setForcedReader("layout");
        ParsedStatement layout = StatementParseEngine.parseLines(withIdentity(), false, null);
        assertEquals("HDFC0001234", v1.meta().ifsc);
        assertEquals("TEST USER", v1.meta().holderName);
        assertEquals(v1.meta().ifsc, layout.meta().ifsc);
        assertEquals(v1.meta().holderName, layout.meta().holderName);
    }

    // ---- 3: an unlabelled card number, a section line as the number ----

    private static final String MASK = "\\d{6}\\*{6}\\d{4}";

    private static List<Line> maskUnderHeader() {
        return List.of(
                bankHeader(0, 100, 0),
                ln(0, 106, "451457******1234", 120),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
    }

    @Test
    void theMaskPatternAsItsOwnLabelReadsALineInsideTheTableAndIsNotUnread() {
        LayoutProfile p = bank("mask").summary(MetaTarget.ACCOUNT_NUMBER, MASK).type(ValueType.MASKED_NUMBER)
                .capture("(" + MASK + ")").done().build();
        var res = ProfiledReader.read(maskUnderHeader(), p);
        assertEquals("451457******1234", res.outcome().meta().accountNumber);
        assertEquals(0, res.unreadAmountLines());
        assertEquals(1, res.outcome().results().size());
    }

    @Test
    void theNumberLineIsNotAContinuationOfTheRowBelowIt() {
        LayoutProfile p = bank("mask").continuation(10, 10).summary(MetaTarget.ACCOUNT_NUMBER, MASK)
                .type(ValueType.TEXT).capture("(" + MASK + ")").done().build();
        assertEquals("SHOP", ProfiledReader.read(maskUnderHeader(), p).outcome().results().get(0).description);
    }

    @Test
    void aSectionLineCanBeTheAccountNumberAndTheSourceOfEachRowsLast4() {
        LayoutProfile p = bank("sec").sectionHeader("card no (\\S+)$").accountNumberFromSectionHeader().build();
        List<Line> lines = List.of(bankHeader(0, 100, 0),
                ln(0, 105, "Card", 50, "No", 90, "451457******1234", 120),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        var o = ProfiledReader.read(lines, p).outcome();
        assertEquals("451457******1234", o.meta().accountNumber);
        assertEquals("1234", o.results().get(0).cardLast4);
    }

    @Test
    void aSummaryAccountNumberWinsOverTheSectionNumber() {
        LayoutProfile p = bank("sec").sectionHeader("card no (\\S+)$").accountNumberFromSectionHeader()
                .summary(MetaTarget.ACCOUNT_NUMBER, "account").type(ValueType.TEXT).rightOnLine().done().build();
        List<Line> lines = List.of(ln(0, 50, "Account", 40, "99990000", 100), bankHeader(0, 100, 0),
                ln(0, 105, "Card", 50, "No", 90, "451457******1234", 120),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        assertEquals("99990000", ProfiledReader.read(lines, p).outcome().meta().accountNumber);
    }

    @Test
    void withoutTheFlagASectionGroupStaysTheLast4AsBefore() {
        LayoutProfile p = bank("sec").sectionHeader("card ending (\\d{4})").build();
        List<Line> lines = List.of(bankHeader(0, 100, 0), ln(0, 105, "Card", 50, "ending", 90, "9999", 140),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        var o = ProfiledReader.read(lines, p).outcome();
        assertEquals("9999", o.results().get(0).cardLast4);
        assertNull(o.meta().accountNumber);
    }

    // ---- 4: last 4 for custom strategies and BANK profiles ----

    @Test
    void headerRegionLast4AppliesToRowsOfACustomRowStrategyToo() {
        LayoutProfile p = bank("custom").cardLast4From("card no (\\d+)")
                .rowStrategy((t, l, d) -> List.of(new LayoutProfile.TableRow(t.get(0), d.parse("05/01/2026"), "A",
                        new BigDecimal("10.00"), null, null, false, null, new BigDecimal("90.00"), null))).build();
        List<Line> lines = List.of(ln(0, 40, "Card", 40, "No", 70, "4514990012345678", 100),
                bankHeader(0, 100, 0), bankRow(0, 110, 0, "05/01/2026", "A", "10.00", null, "90.00"));
        assertEquals("5678", ProfiledReader.read(lines, p).outcome().results().get(0).cardLast4);
    }

    @Test
    void aStrategyRowThatNamesItsOwnLast4KeepsIt() {
        LayoutProfile p = bank("custom").cardLast4From("card no (\\d+)")
                .rowStrategy((t, l, d) -> List.of(new LayoutProfile.TableRow(t.get(0), d.parse("05/01/2026"), "A",
                        new BigDecimal("10.00"), null, null, false, null, new BigDecimal("90.00"), "0001"))).build();
        List<Line> lines = List.of(ln(0, 40, "Card", 40, "No", 70, "4514990012345678", 100),
                bankHeader(0, 100, 0), bankRow(0, 110, 0, "05/01/2026", "A", "10.00", null, "90.00"));
        assertEquals("0001", ProfiledReader.read(lines, p).outcome().results().get(0).cardLast4);
    }

    @Test
    void aBankProfileReproducesTheGenericAccountLast4ForEveryRow() {
        List<Line> lines = new ArrayList<>(List.of(
                ln(0, 30, "Account", 40, "No:", 90, "1234567890", 120),
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                ln(0, 50, "Opening", 40, "Balance:", 100, "100.00", 160),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "50.00", "120.00")));
        LayoutProfile p = bank("acct").cardLast4FromAccountNumber()
                .summary(MetaTarget.ACCOUNT_NUMBER, "account no").type(ValueType.TEXT).rightOnLine().done().build();
        LayoutRouting.setRegistry(new LayoutRegistry(List.of(p)));
        LayoutRouting.setForcedReader("v1");
        List<RowResult> v1 = StatementParseEngine.parseLines(lines, false, null).transactions();
        LayoutRouting.setForcedReader("layout");
        List<RowResult> layout = StatementParseEngine.parseLines(lines, false, null).transactions();
        assertEquals("7890", v1.get(0).cardLast4);
        for (int i = 0; i < v1.size(); i++) {
            assertEquals(v1.get(i).cardLast4, layout.get(i).cardLast4);
        }
    }

    // ---- 5: a printed zero in the unused column ----

    private static List<Line> zeros() {
        return List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", "0.00", "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", "0.00", "50.00", "120.00"),
                bankRow(0, 130, 0, "07/01/2026", "NOTHING", "0.00", "0.00", "120.00"));
    }

    @Test
    void aZeroInTheUnusedColumnIsBlank() {
        List<RowResult> r = ProfiledReader.read(zeros(), bankProfile()).outcome().results();
        assertEquals(3, r.size());
        assertEquals(-30.00, r.get(0).amount, 0.001);
        assertEquals(50.00, r.get(1).amount, 0.001);
    }

    @Test
    void bothColumnsZeroIsAZeroRowKeptLikeTheGenericReaderKeepsIt() {
        List<RowResult> r = ProfiledReader.read(zeros(), bankProfile()).outcome().results();
        assertEquals(0.0, r.get(2).amount, 0.0);
        assertTrue(r.get(2).chainValid);
    }

    @Test
    void theAmountsMatchTheGenericReaderOnTheSameLines() {
        List<Line> lines = new ArrayList<>(List.of(
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                ln(0, 50, "Opening", 40, "Balance:", 100, "100.00", 160)));
        lines.addAll(List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", "0.00", "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", "0.00", "50.00", "120.00"),
                bankRow(0, 130, 0, "07/01/2026", "NOTHING", "0.00", "0.00", "120.00")));
        LayoutRouting.setRegistry(new LayoutRegistry(List.of(bankProfile())));
        LayoutRouting.setForcedReader("v1");
        List<RowResult> v1 = StatementParseEngine.parseLines(lines, false, null).transactions();
        LayoutRouting.setForcedReader("layout");
        List<RowResult> layout = StatementParseEngine.parseLines(lines, false, null).transactions();
        assertEquals(v1.size(), layout.size());
        for (int i = 0; i < v1.size(); i++) {
            assertEquals(v1.get(i).amount, layout.get(i).amount, 0.001);
        }
    }

    @Test
    void bothColumnsHoldingARealAmountIsUnread() {
        List<Line> lines = List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "BOTH", "10.00", "20.00", "90.00"),
                bankRow(0, 120, 0, "06/01/2026", "FINE", "5.00", "0.00", "85.00"));
        var res = ProfiledReader.read(lines, bankProfile());
        assertEquals(1, res.outcome().results().size());
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void aNarrationLineBelowARowWithZerosJoinsItWithTheStandardRowShape() {
        LayoutProfile p = bank("zc").continuation(10, 0).build();
        List<Line> lines = List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "UPI SHOP", "30.00", "0.00", "70.00"),
                ln(0, 118, "REF 12345", NARR),
                bankRow(0, 140, 0, "06/01/2026", "NEXT", "0.00", "50.00", "120.00"));
        List<RowResult> r = ProfiledReader.read(lines, p).outcome().results();
        assertEquals("UPI SHOP REF 12345", r.get(0).description);
        assertEquals("NEXT", r.get(1).description);
    }
}
