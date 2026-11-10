package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** G: an amount printed without a digit before the point (".00"); H: a summary line inside the table area is no unread row. */
class ProfiledReaderBareFractionSummaryLineTest {

    @Test
    void aFigureWithNoDigitBeforeThePointIsAnAmount() {
        for (String t : new String[]{".00", ".5", "C.00", "₹ .00", ".00DR", "(.25)"}) {
            Amounts.Parsed a = Amounts.parse(t);
            assertNotNull(a, t);
        }
        assertEquals(0, Amounts.parse(".25").value().compareTo(new BigDecimal("0.25")));
        assertEquals("DR", Amounts.parse(".00DR").suffix());
        assertTrue(Amounts.parse("(.25)").negativeMarked());
        assertNull(Amounts.parse("."));
        assertNull(Amounts.parse("12"));
    }

    @Test
    void summaryValuesReadTheBareFigure() {
        List<Line> lines = List.of(
                ln(0, 50, "Total", 40, "Due", 80, ".00", 120, "Minimum", 200, "Due", 260, "C.00", 300),
                ln(0, 60, "Credit", 40, "Limit", 80, "50,000.00", 120),
                ln(0, 70, "Totals", 40),
                ln(0, 80, "9.00", 40, ".00", 100),
                singleHeader(0, 300, 0),
                singleRow(0, 310, 0, "05/01/2026", "SHOP", "100.00", "Dr"));
        LayoutProfile p = single("bare").suffix("Cr", "Dr", Direction.DEBIT)
                .summary("total_amount_due", "total due").rightOnLine().done()
                .summary("minimum_amount_due", "minimum due").rightOnLine().done()
                .summary("fees_and_charges", "totals").nthAmountBelow(2).done()
                .build();
        ReadOutcome o = ProfiledReader.read(lines, p).outcome();
        assertEquals(0.0, (Double) o.summaryFields().get("fees_and_charges"), 0.0001);
        assertEquals(0.0, (Double) o.summaryFields().get("total_amount_due"), 0.0001);
        assertEquals(0.0, (Double) o.summaryFields().get("minimum_amount_due"), 0.0001);
    }

    private static List<Line> twoPages(boolean summaryUnderRowsOfPageOne) {
        return List.of(
                singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "SHOP", "100.00", "Dr"),
                ln(1, 50, "Net", 40, "Balance", 80, "1.00", 150, "2.00", 200, "3.00", 250, "4.00", 300),
                singleHeader(1, 100, 0),
                singleRow(1, 110, 0, "06/01/2026", "SHOP TWO", "50.00", "Dr"));
    }

    @Test
    void aLineReadBySummaryRuleIsNotCountedAsUnread() {
        LayoutProfile.Builder b = single("sumline").suffix("Cr", "Dr", Direction.DEBIT);
        ProfiledReader.Result without = ProfiledReader.read(twoPages(true), b.build());
        assertEquals(1, without.unreadAmountLines());
        LayoutProfile with = single("sumline").suffix("Cr", "Dr", Direction.DEBIT)
                .summary("fees_and_charges", "net balance").rightOnLine().done().build();
        ProfiledReader.Result r = ProfiledReader.read(twoPages(true), with);
        assertEquals(0, r.unreadAmountLines());
        assertEquals(2, r.outcome().results().size());
    }

    @Test
    void aDeclaredSummaryLineInsideTheTableIsNotUnreadEvenWhenTheRuleReadAnEarlierCopy() {
        List<Line> lines = List.of(
                ln(0, 50, "Net", 40, "Balance", 80, "1.00", 150, "2.00", 200, "3.00", 250, "4.00", 300),
                singleHeader(0, 100, 0),
                singleRow(0, 110, 0, "05/01/2026", "SHOP", "100.00", "Dr"),
                singleHeader(1, 100, 0),
                ln(1, 90, "Net", 40, "Balance", 80, "5.00", 150, "6.00", 200, "7.00", 250, "8.00", 300),
                singleRow(1, 110, 0, "06/01/2026", "SHOP TWO", "50.00", "Dr"));
        String rule = "net\\s+balance";
        ProfiledReader.Result plain = ProfiledReader.read(lines, single("sl").suffix("Cr", "Dr", Direction.DEBIT)
                .summary("fees_and_charges", rule).rightOnLine().done().build());
        assertEquals(1, plain.unreadAmountLines());
        ProfiledReader.Result declared = ProfiledReader.read(lines, single("sl").suffix("Cr", "Dr", Direction.DEBIT)
                .summary("fees_and_charges", rule).rightOnLine().done().summaryLineInTable(rule).build());
        assertEquals(0, declared.unreadAmountLines());
        assertEquals(2, declared.outcome().results().size());
        assertEquals(1.0, (Double) declared.outcome().summaryFields().get("fees_and_charges"), 0.0001);
    }
}
