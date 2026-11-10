package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Summary values are read from the table pages and trailing summary pages only, never from terms text. */
class ProfiledReaderScopeTest {

    private static Line prose(int page, double top, String lastWords, double x) {
        List<Object> spec = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            spec.add("word" + i);
            spec.add(10.0 + i * 25);
        }
        spec.add("Credit");
        spec.add(480.0);
        spec.add("Limit");
        spec.add(520.0);
        spec.add(lastWords);
        spec.add(x);
        return ln(page, top, spec.toArray());
    }

    private static LayoutProfile profile() {
        return single("scope").suffix("Cr", "Dr", Direction.DEBIT).continuesWithoutHeader(false)
                .summary("credit_limit", "credit limit").rightOnLine().done().build();
    }

    private static List<Line> table() {
        return List.of(singleHeader(0, 100, 0), singleRow(0, 110, 0, "05/01/2026", "SHOP", "100.00", "Dr"));
    }

    @Test
    void aTermsPageAfterTheTableIsNeverScanned() {
        List<Line> lines = new ArrayList<>(table());
        lines.add(prose(1, 50, "9,999.00", 570));
        lines.add(prose(1, 60, "x", 575));
        assertNull(ProfiledReader.read(lines, profile()).outcome().summaryFields().get("credit_limit"));
    }

    @Test
    void aShortSummaryPageRightAfterTheTableIsScanned() {
        List<Line> lines = new ArrayList<>(table());
        lines.add(ln(1, 50, "Credit", 40, "Limit", 80, "7,000.00", 140));
        assertEquals(7000.00, (Double) ProfiledReader.read(lines, profile()).outcome().summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void theScanStopsAtTheFirstTermsPageEvenIfASummaryPageFollowsIt() {
        List<Line> lines = new ArrayList<>(table());
        lines.add(prose(1, 50, "x", 575));
        lines.add(prose(1, 60, "y", 575));
        lines.add(ln(2, 50, "Credit", 40, "Limit", 80, "7,000.00", 140));
        assertNull(ProfiledReader.read(lines, profile()).outcome().summaryFields().get("credit_limit"));
    }

    @Test
    void aSummaryAboveTheTableOnTheFirstPageIsScanned() {
        List<Line> lines = new ArrayList<>();
        lines.add(ln(0, 50, "Credit", 40, "Limit", 80, "3,000.00", 140));
        lines.addAll(table());
        assertEquals(3000.00, (Double) ProfiledReader.read(lines, profile()).outcome().summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void pagesNarrowTheScanWithinTheAllowedWindow() {
        List<Line> lines = new ArrayList<>();
        lines.add(ln(0, 50, "Credit", 40, "Limit", 80, "3,000.00", 140));
        lines.addAll(table());
        lines.add(ln(1, 50, "Credit", 40, "Limit", 80, "7,000.00", 140));
        LayoutProfile second = single("scope").suffix("Cr", "Dr", Direction.DEBIT).continuesWithoutHeader(false)
                .summary("credit_limit", "credit limit").rightOnLine().pages(1).done().build();
        assertEquals(7000.00, (Double) ProfiledReader.read(lines, second).outcome().summaryFields().get("credit_limit"), 0.001);
    }

    @Test
    void pagesCannotWidenTheScanOntoATermsPage() {
        List<Line> lines = new ArrayList<>(table());
        lines.add(prose(1, 50, "9,999.00", 570));
        lines.add(prose(1, 60, "x", 575));
        LayoutProfile second = single("scope").suffix("Cr", "Dr", Direction.DEBIT).continuesWithoutHeader(false)
                .summary("credit_limit", "credit limit").rightOnLine().pages(1).done().build();
        assertNull(ProfiledReader.read(lines, second).outcome().summaryFields().get("credit_limit"));
    }
}
