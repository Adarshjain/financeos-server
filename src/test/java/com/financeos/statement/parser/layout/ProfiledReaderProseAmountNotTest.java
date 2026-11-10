package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ReadOutcome;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Prose cut-off on header-less pages (D), the minus / stray comma after a currency glyph (E), requiresNot (F). */
class ProfiledReaderProseAmountNotTest {

    // ---- D ----

    private static Line prose(int page, double top) {
        List<Object> spec = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            spec.add("word" + i);
            spec.add(20.0 + i * 25);
        }
        return ln(page, top, spec.toArray());
    }

    @Test
    void datedRowsAboveTheTermsTextOfAHeaderlessPageAreRead() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", "10.00", null, "90.00"),
                bankRow(1, 40, 0, "06/01/2026", "SHOP TWO", "5.00", null, "85.00"),
                prose(1, 80), prose(1, 90), prose(1, 100),
                bankRow(1, 120, 0, "07/01/2026", "AFTER TERMS", "1.00", null, "84.00"));
        List<RowResult> r = ProfiledReader.read(lines, bankProfile()).outcome().results();
        assertEquals(2, r.size());
        assertEquals("SHOP TWO", r.get(1).description);
    }

    @Test
    void aHeaderlessPageThatStartsWithProseIsStillSkipped() {
        List<Line> lines = List.of(
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", "10.00", null, "90.00"),
                prose(1, 40), prose(1, 50), prose(1, 60),
                bankRow(1, 120, 0, "06/01/2026", "IN TERMS", "5.00", null, "85.00"));
        assertEquals(1, ProfiledReader.read(lines, bankProfile()).outcome().results().size());
    }

    // ---- E ----

    @Test
    void aMinusAndAStrayCommaAfterTheCurrencyGlyphParseAsANegativeAmount() {
        for (String t : new String[]{"C-,123.45", "-,123.45", "₹-,123.45"}) {
            Amounts.Parsed a = Amounts.parse(t);
            assertNotNull(a, t);
            assertEquals(0, a.value().compareTo(new java.math.BigDecimal("123.45")), t);
            assertTrue(a.negativeMarked(), t);
        }
        assertNull(Amounts.parse(",123.45"));
        assertFalse(Amounts.parse("C1,234.50").negativeMarked());
        assertTrue(Amounts.parse("C-1,234.50").negativeMarked());
    }

    private static ReadOutcome read(java.util.function.UnaryOperator<LayoutProfile.Builder> summary, Line... summaryLines) {
        List<Line> lines = new ArrayList<>(List.of(summaryLines));
        lines.add(singleHeader(0, 300, 0));
        lines.add(singleRow(0, 310, 0, "05/01/2026", "SHOP", "100.00", "Dr"));
        LayoutProfile.Builder b = single("sum").suffix("Cr", "Dr", Direction.DEBIT);
        return ProfiledReader.read(lines, summary.apply(b).build()).outcome();
    }

    @Test
    void theOddShapeIsReadByNthAmountBelowAndByCapture() {
        ReadOutcome o = read(b -> b
                        .summary("previous_balance", "previous balance").nthAmountBelow(1).creditIsNegative().done()
                        .summary("total_amount_due", "total due").capture("total due\\s+(\\S+)").creditIsNegative().done(),
                ln(0, 50, "Previous", 40, "Balance", 90, "Total", 150, "Due", 190, "C-,50.00", 230),
                ln(0, 62, "C-,20.00", 40));
        assertEquals(-20.00, (Double) o.summaryFields().get("previous_balance"), 0.001);
        assertEquals(-50.00, (Double) o.summaryFields().get("total_amount_due"), 0.001);
    }

    // ---- F ----

    @Test
    void requiresNotStopsAProfileFromMatchingWhenTheTextIsThere() {
        LayoutRegistry reg = new LayoutRegistry(List.of(bank("not").requiresNot("forbidden\\s+word").build()));
        List<Line> clean = List.of(bankHeader(0, 100, 0));
        List<Line> tainted = List.of(ln(0, 50, "a", 10, "Forbidden", 40, "Word", 90), bankHeader(0, 100, 0));
        assertNotNull(reg.match(clean, null));
        assertNull(reg.match(tainted, null));
    }
}
