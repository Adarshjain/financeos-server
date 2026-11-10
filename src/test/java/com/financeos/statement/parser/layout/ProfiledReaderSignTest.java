package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Sign;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Each sign rule, and which mode string the reader hands to the shared finish step. */
class ProfiledReaderSignTest {

    private static ReadOutcome read(List<Line> lines, LayoutProfile p) {
        return ProfiledReader.read(lines, p).outcome();
    }

    // ---- SEPARATE_COLUMNS ----

    @Test
    void separateColumnsDebitIsNegativeAndCreditPositive() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "10.00", null, "90.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "25.00", "115.00"));
        List<RowResult> r = read(lines, bankProfile()).results();
        assertEquals(-10.00, r.get(0).amount, 0.001);
        assertEquals(25.00, r.get(1).amount, 0.001);
        assertEquals("column", r.get(0).signSource);
        assertEquals("column", r.get(1).signSource);
    }

    // ---- SUFFIX ----

    private static LayoutProfile suffix(Direction dflt) {
        return single("suffix").suffix("Cr", "Dr", dflt).build();
    }

    @Test
    void suffixCreditTokenMakesTheAmountPositive() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "PAYMENT", "100.00", "Cr"));
        RowResult r = read(lines, suffix(Direction.DEBIT)).results().get(0);
        assertEquals(100.00, r.amount, 0.001);
        assertEquals("suffix", r.signSource);
    }

    @Test
    void suffixDebitTokenMakesTheAmountNegative() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "SHOP", "100.00", "Dr"));
        RowResult r = read(lines, suffix(Direction.CREDIT)).results().get(0);
        assertEquals(-100.00, r.amount, 0.001);
        assertEquals("suffix", r.signSource);
    }

    @Test
    void suffixTokenIsCaseInsensitive() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "PAYMENT", "100.00", "CR"));
        assertEquals(100.00, read(lines, suffix(Direction.DEBIT)).results().get(0).amount, 0.001);
    }

    @Test
    void suffixGluedToTheAmountIsRead() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "PAYMENT", "100.00Cr", null));
        RowResult r = read(lines, suffix(Direction.DEBIT)).results().get(0);
        assertEquals(100.00, r.amount, 0.001);
        assertEquals("suffix", r.signSource);
    }

    @Test
    void withoutATokenTheDefaultDirectionAppliesAndIsLabelledDefault() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "NO TOKEN", "100.00", null));
        RowResult debit = read(lines, suffix(Direction.DEBIT)).results().get(0);
        RowResult credit = read(lines, suffix(Direction.CREDIT)).results().get(0);
        assertEquals(-100.00, debit.amount, 0.001);
        assertEquals(100.00, credit.amount, 0.001);
        assertEquals("default", debit.signSource);
    }

    @Test
    void anUnknownMarkerWordFallsBackToTheDefaultDirection() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "ODD", "100.00", "XX"));
        assertEquals("default", read(lines, suffix(Direction.DEBIT)).results().get(0).signSource);
    }

    @Test
    void suffixRuleDroppedRowWithoutAnAmount() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "EMPTY", 120),
                singleRow(0, 120, 0, "06/01/2026", "OK", "100.00", "Dr"));
        ReadOutcome o = read(lines, suffix(Direction.DEBIT));
        assertEquals(1, o.results().size());
        assertEquals(1, o.rowsDropped());
    }

    // ---- MINUS_IS_CREDIT ----

    private static LayoutProfile minus() {
        return single("minus").sign(Sign.MINUS_IS_CREDIT).build();
    }

    @Test
    void minusPrintedMeansCredit() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "REFUND", "-100.00", null));
        RowResult r = read(lines, minus()).results().get(0);
        assertEquals(100.00, r.amount, 0.001);
        assertEquals("minus", r.signSource);
    }

    @Test
    void bracketsAndTrailingMinusAlsoMeanCredit() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "A", "(100.00)", null),
                singleRow(0, 120, 0, "06/01/2026", "B", "50.00-", null));
        List<RowResult> r = read(lines, minus()).results();
        assertEquals(100.00, r.get(0).amount, 0.001);
        assertEquals(50.00, r.get(1).amount, 0.001);
    }

    @Test
    void noMinusMeansDebit() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "SHOP", "100.00", null));
        assertEquals(-100.00, read(lines, minus()).results().get(0).amount, 0.001);
    }

    // ---- BALANCE_DELTA ----

    private static LayoutProfile delta() {
        return LayoutProfile.builder("delta")
                .product(LayoutProfile.Product.BANK)
                .headerLabel("DATE", "\\bdate\\b")
                .headerLabel("NARR", "\\bnarration\\b")
                .headerLabel("AMT", "\\bamount\\b")
                .headerLabel("BAL", "\\bbalance\\b")
                .fromLabel(LayoutProfile.Role.DATE, "DATE", -10, 60)
                .fromLabel(LayoutProfile.Role.NARRATION, "NARR", -10, 160)
                .fromLabel(LayoutProfile.Role.AMOUNT, "AMT", -20, 50)
                .fromLabel(LayoutProfile.Role.BALANCE, "BAL", -30, 80)
                .sign(Sign.BALANCE_DELTA)
                .summary(MetaTarget.OPENING_BALANCE, "opening balance").rightOnLine().done()
                .build();
    }

    private static Line deltaHeader() {
        return ln(0, 100, "Date", 50, "Narration", 120, "Amount", 400, "Balance", 480);
    }

    private static Line deltaRow(double top, String date, String narr, String amount, String balance) {
        return ln(0, top, date, 50, narr, 120, amount, 405, balance, 485);
    }

    @Test
    void balanceDeltaTakesTheSignFromTheChangeInBalance() {
        List<Line> lines = List.of(
                ln(0, 60, "Opening", 50, "Balance", 100, "100.00", 160),
                deltaHeader(),
                deltaRow(110, "05/01/2026", "OUT", "30.00", "70.00"),
                deltaRow(120, "06/01/2026", "IN", "50.00", "120.00"));
        ReadOutcome o = read(lines, delta());
        assertEquals(-30.00, o.results().get(0).amount, 0.001);
        assertEquals(50.00, o.results().get(1).amount, 0.001);
        assertEquals("balance", o.results().get(0).signSource);
        assertTrue(o.results().get(0).chainValid);
        assertTrue(o.results().get(1).chainValid);
    }

    @Test
    void balanceDeltaFirstRowWithoutAnOpeningBalanceIsNotGuessedAndIsUnread() {
        List<Line> lines = List.of(
                deltaHeader(),
                deltaRow(110, "05/01/2026", "OUT", "30.00", "70.00"),
                deltaRow(120, "06/01/2026", "IN", "50.00", "120.00"));
        var res = ProfiledReader.read(lines, delta());
        assertEquals(1, res.outcome().results().size());
        assertEquals(50.00, res.outcome().results().get(0).amount, 0.001);
        assertEquals("balance", res.outcome().results().get(0).signSource);
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void balanceDeltaWithOnlyAnUnsignableRowReadsNothing() {
        List<Line> lines = List.of(deltaHeader(), deltaRow(110, "05/01/2026", "OUT", "30.00", "70.00"));
        assertThrows(IllegalStateException.class, () -> ProfiledReader.read(lines, delta()));
    }

    @Test
    void aCrOrDrTokenOutsideEveryColumnIsUnreadNotADefaultedDirection() {
        List<Line> lines = List.of(singleHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "SHOP", 120, "100.00", 405, "Cr", 560));
        var res = ProfiledReader.read(lines, single("s").suffix("Cr", "Dr", Direction.DEBIT).build());
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void theChainToleranceIsTheSameAsV1() {
        LayoutProfile p = bank("tol").build();
        List<Line> within = List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "A", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "B", "10.00", null, "60.01"));
        List<Line> beyond = List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "A", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "B", "10.00", null, "60.02"));
        assertTrue(read(within, p).results().get(1).chainValid);
        assertFalse(read(beyond, p).results().get(1).chainValid);
    }

    // ---- mode selection ----

    @Test
    void aBalanceColumnThatChainsGivesBalanceChainMode() {
        List<Line> lines = List.of(
                ln(0, 60, "Opening", 50, "Balance", 100, "100.00", 160),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "50.00", "120.00"));
        LayoutProfile p = bank("chain").summary(MetaTarget.OPENING_BALANCE, "opening balance").rightOnLine().done().build();
        ReadOutcome o = read(lines, p);
        assertEquals("balance-chain", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(100.00, o.meta().openingBalance, 0.001);
    }

    @Test
    void aChainWithoutAnOpeningBalanceDerivesTheOpeningFromTheFirstRow() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "50.00", "120.00"));
        ReadOutcome o = read(lines, bankProfile());
        assertEquals("balance-chain", o.mode());
        assertEquals(100.00, o.meta().openingBalance, 0.001);
    }

    @Test
    void aBalanceColumnThatDoesNotChainFallsBackToReconciliationOrHeuristic() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", null, "70.00"),
                bankRow(0, 120, 0, "06/01/2026", "IN", null, "50.00", "999.00"),
                bankRow(0, 130, 0, "07/01/2026", "OUT", "10.00", null, "888.00"));
        ReadOutcome o = read(lines, bankProfile());
        assertEquals("heuristic (no balance column validated)", o.mode());
        assertTrue(o.results().stream().noneMatch(r -> r.chainValid));
        assertTrue(o.results().stream().allMatch(r -> r.balance == null));
    }

    private static LayoutProfile noBalance() {
        return single("nobal").suffix("Cr", "Dr", Direction.DEBIT)
                .summary("previous_balance", "previous balance").rightOnLine().done()
                .summary("total_amount_due", "total due").rightOnLine().done()
                .build();
    }

    @Test
    void withoutABalanceColumnOpeningAndClosingThatReconcileGiveTheReconciliationMode() {
        List<Line> lines = List.of(
                ln(0, 50, "Previous", 50, "Balance", 100, "1,000.00", 160),
                ln(0, 60, "Total", 50, "Due", 90, "1,300.00", 160),
                singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "PAYMENT", "200.00", "Cr"),
                singleRow(0, 120, 0, "06/01/2026", "SHOP", "500.00", "Dr"));
        ReadOutcome o = read(lines, noBalance());
        assertEquals("opening/closing reconciliation", o.mode());
        assertTrue(o.results().stream().allMatch(r -> r.chainValid));
        assertEquals(800.00, o.results().get(0).balance, 0.001);
        assertEquals(1300.00, o.results().get(1).balance, 0.001);
        assertEquals(1000.00, o.meta().openingBalance, 0.001);
        assertEquals(1300.00, o.meta().closingBalance, 0.001);
    }

    @Test
    void withoutABalanceColumnAndNothingToReconcileTheHeuristicModeIsUsed() {
        List<Line> lines = List.of(
                singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "PAYMENT", "200.00", "Cr"));
        ReadOutcome o = read(lines, noBalance());
        assertEquals("heuristic (no balance column validated)", o.mode());
        assertFalse(o.results().get(0).chainValid);
    }

    @Test
    void openingAndClosingThatDoNotAddUpDoNotReconcile() {
        List<Line> lines = List.of(
                ln(0, 50, "Previous", 50, "Balance", 100, "1,000.00", 160),
                ln(0, 60, "Total", 50, "Due", 90, "9,999.00", 160),
                singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "PAYMENT", "200.00", "Cr"));
        assertEquals("heuristic (no balance column validated)", read(lines, noBalance()).mode());
    }

    @Test
    void aCardRowDefaultedByNoTokenIsFlippedWhenOnlyTheFlipReconciles() {
        // card convention: opening - net == closing; two untokened rows only add up when read as credits
        LayoutProfile p = single("flip").suffix("Cr", "Dr", Direction.DEBIT)
                .summary("previous_balance", "previous balance").rightOnLine().done()
                .summary("total_amount_due", "total due").rightOnLine().done().build();
        List<Line> lines = List.of(
                ln(0, 50, "Previous", 50, "Balance", 100, "1,000.00", 160),
                ln(0, 60, "Total", 50, "Due", 90, "700.00", 160),
                singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "UNMARKED", "300.00", null));
        ReadOutcome o = read(lines, p);
        assertEquals("opening/closing reconciliation", o.mode());
        assertEquals(300.00, o.results().get(0).amount, 0.001);
    }

    @Test
    void theOutcomeCarriesTheTransactionLinesAndTheHarvestWindow() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "OUT", "30.00", null, "70.00"),
                bankRow(1, 110, 0, "05/01/2026", "NOT A TABLE", "30.00", null, "70.00"));
        ReadOutcome o = read(lines, bank("nocont").continuesWithoutHeader(false).build());
        assertEquals(1, o.txnLines().size());
        assertTrue(o.txnLines().contains(lines.get(1)));
        assertEquals(0, o.maxTxnPage());
        assertFalse(o.isCreditCard());
        assertTrue(o.statedNone().isEmpty());
    }
}
