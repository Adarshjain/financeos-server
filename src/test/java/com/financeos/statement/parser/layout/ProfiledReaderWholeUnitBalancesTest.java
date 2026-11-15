package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Sign;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.ln;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A balance-delta statement whose opening balance is printed in whole units only: the figure sets the direction of the
 * first row, the exact opening comes from that row's printed balance. Invented values. The shared amount pass has already
 * turned the printed whole figures into "n.00" by the time the reader sees them.
 */
class ProfiledReaderWholeUnitBalancesTest {

    private static LayoutProfile.Builder delta() {
        return LayoutProfile.builder("whole")
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
                .summary(MetaTarget.CLOSING_BALANCE, "closing balance").rightOnLine().done();
    }

    private static Line header() {
        return ln(0, 100, "Date", 50, "Narration", 120, "Amount", 400, "Balance", 480);
    }

    private static Line row(double top, String narr, String amount, String balance) {
        return ln(0, top, "05/01/2026", 50, narr, 120, amount, 405, balance, 485);
    }

    private static Line banner(String opening, String closing) {
        return ln(0, 60, "Opening", 50, "Balance", 100, opening, 160, "Closing", 250, "Balance", 300, closing, 360);
    }

    private static ReadOutcome read(List<Line> lines, LayoutProfile p) {
        return ProfiledReader.read(lines, p).outcome();
    }

    @Test
    void aCreditFirstRowChainsFromTheExactOpeningDerivedFromItsBalance() {
        ReadOutcome o = read(List.of(banner("100.00", "130.00"), header(),
                row(110, "IN", "20.00", "120.80"), row(120, "IN", "10.00", "130.80")),
                delta().wholeUnitStatedBalances().build());
        assertEquals(2, o.results().size());
        assertEquals(20.00, o.results().get(0).amount, 0.001);
        assertTrue(o.results().get(0).chainValid);
        assertTrue(o.results().get(1).chainValid);
        assertEquals(100.80, o.meta().openingBalance, 0.001);
        assertEquals(130.80, o.meta().closingBalance, 0.001);
        assertEquals("balance-chain", o.mode());
    }

    @Test
    void aDebitFirstRowIsNegative() {
        ReadOutcome o = read(List.of(banner("100.00", "70.00"), header(), row(110, "OUT", "30.00", "70.80")),
                delta().wholeUnitStatedBalances().build());
        assertEquals(-30.00, o.results().get(0).amount, 0.001);
        assertEquals(100.80, o.meta().openingBalance, 0.001);
    }

    @Test
    void aFirstRowThatFitsBothDirectionsIsNotGuessed() {
        // 100 -> 100.40 with a 0.40 amount: credit from 100.00, debit from 100.80 are both within one unit of 100
        var res = ProfiledReader.read(List.of(banner("100.00", "100.00"), header(),
                row(110, "X", "0.40", "100.40"), row(120, "Y", "5.00", "105.40")),
                delta().wholeUnitStatedBalances().build());
        assertEquals(1, res.outcome().results().size());
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void withoutTheFlagTheRoundedOpeningIsUsedAsPrinted() {
        ReadOutcome o = read(List.of(banner("100.00", "130.00"), header(),
                row(110, "IN", "20.00", "121.80"), row(120, "IN", "10.00", "131.80")), delta().build());
        assertNotEquals("balance-chain", o.mode());
        assertEquals(100.0, o.meta().openingBalance, 0.001);
    }
}
