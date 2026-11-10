package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.layout.LayoutProfile.Direction;
import com.financeos.statement.parser.layout.LayoutProfile.MetaTarget;
import com.financeos.statement.parser.layout.LayoutProfile.Role;
import com.financeos.statement.parser.layout.LayoutProfile.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Card last 4 from the header region, PREFIX markers, continuation geometry, IGNORE cells, header-geometry variants. */
class ProfiledReaderGeometryTest {

    private static List<RowResult> rows(List<Line> lines, LayoutProfile p) {
        return ProfiledReader.read(lines, p).outcome().results();
    }

    // ---- A: card last 4 from the header region ----

    private static List<Line> cardAboveTable() {
        return List.of(
                ln(0, 40, "Card", 40, "No", 70, "4514", 100, "XXXX", 130, "XX", 160, "3536", 190),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
    }

    @Test
    void rowsTakeTheLast4FromARegexOverTheLinesBeforeTheTable() {
        LayoutProfile p = bank("l4").cardLast4From("card no ([\\dX ]+)$").build();
        assertEquals("3536", rows(cardAboveTable(), p).get(0).cardLast4);
    }

    @Test
    void rowsTakeTheLast4FromTheAccountNumberSummaryValue() {
        LayoutProfile p = bank("l4").cardLast4FromAccountNumber()
                .summary(MetaTarget.ACCOUNT_NUMBER, "card no").type(ValueType.MASKED_NUMBER)
                .capture("card no\\s+(.+)$").done().build();
        assertEquals("3536", rows(cardAboveTable(), p).get(0).cardLast4);
    }

    @Test
    void theRegexComesBeforeTheAccountNumber() {
        LayoutProfile p = bank("l4").cardLast4From("card no (\\d{4})").cardLast4FromAccountNumber()
                .summary(MetaTarget.ACCOUNT_NUMBER, "card no").type(ValueType.MASKED_NUMBER)
                .capture("card no\\s+(.+)$").done().build();
        assertEquals("4514", rows(cardAboveTable(), p).get(0).cardLast4);
    }

    @Test
    void aSectionHeaderAboveTheRowWinsOverTheHeaderRegion() {
        LayoutProfile p = bank("l4").cardLast4From("card no ([\\dX ]+)$").sectionHeader("card ending (\\d{4})").build();
        List<Line> lines = List.of(
                ln(0, 40, "Card", 40, "No", 70, "4514", 100, "XXXX", 130, "XX", 160, "3536", 190),
                bankHeader(0, 100, 0),
                bankRow(0, 105, 0, "05/01/2026", "BEFORE SECTION", "10.00", null, "90.00"),
                ln(0, 108, "Card", 50, "ending", 80, "9999", 120),
                bankRow(0, 110, 0, "06/01/2026", "AFTER SECTION", "10.00", null, "80.00"));
        List<RowResult> r = rows(lines, p);
        assertEquals("3536", r.get(0).cardLast4);
        assertEquals("9999", r.get(1).cardLast4);
    }

    @Test
    void withNoDeclaredSourceRowsHaveNoLast4AndALineInsideTheTableIsNotARegionSource() {
        assertNull(rows(cardAboveTable(), bankProfile()).get(0).cardLast4);
        LayoutProfile p = bank("l4").cardLast4From("card no ([\\dX ]+)$").build();
        List<Line> inside = List.of(bankHeader(0, 100, 0),
                ln(0, 105, "Card", 40, "No", 70, "4514", 100, "XXXX", 130, "XX", 160, "3536", 190),
                bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));
        assertNull(rows(inside, p).get(0).cardLast4);
    }

    // ---- B: PREFIX markers ----

    private static LayoutProfile prefix() {
        return single("pre").prefix("\\+", null, Direction.DEBIT).build();
    }

    private static List<Line> one(Line row) {
        return List.of(singleHeader(0, 100, 0), row);
    }

    @Test
    void aPlusRightBeforeTheAmountIsACreditAndNothingIsADebit() {
        List<RowResult> r = rows(List.of(singleHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "REFUND", 120, "+", 390, "100.00", 405),
                ln(0, 120, "06/01/2026", 50, "SHOP", 120, "40.00", 405)), prefix());
        assertEquals(100.00, r.get(0).amount, 0.001);
        assertEquals("prefix", r.get(0).signSource);
        assertEquals(-40.00, r.get(1).amount, 0.001);
        assertEquals("default", r.get(1).signSource);
    }

    @Test
    void aCurrencyGlyphMayStandBetweenTheMarkerAndTheAmount() {
        List<RowResult> r = rows(one(ln(0, 110, "05/01/2026", 50, "REFUND", 120, "+", 383, "Rs", 390, "100.00", 405)), prefix());
        assertEquals(100.00, r.get(0).amount, 0.001);
    }

    @Test
    void theWordAfterTheAmountIsUnrelatedToTheSign() {
        var res = ProfiledReader.read(one(ln(0, 110, "05/01/2026", 50, "SHOP", 120, "40.00", 405, "note", 460)), prefix());
        assertEquals(-40.00, res.outcome().results().get(0).amount, 0.001);
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aMarkerLikeTokenThatIsNotAdjacentAndNotInNarrationCountsTheRowUnread() {
        var res = ProfiledReader.read(one(ln(0, 110, "05/01/2026", 50, "SHOP", 120, "+", 383, "foo", 390, "40.00", 405)), prefix());
        assertEquals(1, res.unreadAmountLines());
    }

    @Test
    void aMarkerLikeTokenInsideTheNarrationRangeIsNarration() {
        var res = ProfiledReader.read(one(ln(0, 110, "05/01/2026", 50, "ALPHA", 120, "+", 150, "BETA", 158, "40.00", 405)), prefix());
        assertEquals(0, res.unreadAmountLines());
        assertEquals("ALPHA + BETA", res.outcome().results().get(0).description);
        assertEquals(-40.00, res.outcome().results().get(0).amount, 0.001);
    }

    @Test
    void prefixTokensAreRegexesAndADebitRegexIsHonoured() {
        LayoutProfile p = single("pre").prefix("cr|\\+", "dr|-", Direction.CREDIT).build();
        List<RowResult> r = rows(List.of(singleHeader(0, 100, 0),
                ln(0, 110, "05/01/2026", 50, "A", 120, "CR", 385, "10.00", 405),
                ln(0, 120, "06/01/2026", 50, "B", 120, "-", 385, "20.00", 405),
                ln(0, 130, "07/01/2026", 50, "C", 120, "30.00", 405)), p);
        assertEquals(10.00, r.get(0).amount, 0.001);
        assertEquals(-20.00, r.get(1).amount, 0.001);
        assertEquals(30.00, r.get(2).amount, 0.001);
        assertEquals("default", r.get(2).signSource);
    }

    @Test
    void aPrefixRuleNeedsAnAmountColumn() {
        assertThrows(IllegalStateException.class, () -> bank("x").prefix("\\+", null, Direction.DEBIT).build());
    }

    // ---- C: continuation geometry ----

    private static List<Line> continued(double dateX, double narrX, double debitX, double balX) {
        return List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "PAGE ONE", "10.00", null, "90.00"),
                ln(1, 40, "06/01/2026", dateX, "PAGE TWO", narrX, "5.00", debitX, "85.00", balX));
    }

    private static LayoutProfile.Builder absoluteContinuation() {
        return bank("cont")
                .continuationFromX(Role.DATE, 10, 100).continuationFromX(Role.NARRATION, 100, 300)
                .continuationFromX(Role.DEBIT, 300, 370).continuationFromX(Role.CREDIT, 370, 440)
                .continuationFromX(Role.BALANCE, 440, 560);
    }

    @Test
    void withoutContinuationColumnsAShiftedHeaderlessPageIsNotReadWithTheHeaderGeometry() {
        assertEquals(1, rows(continued(25, 136, 325, 465), bankProfile()).size());
    }

    @Test
    void absoluteContinuationColumnsReadAHeaderlessPageWithItsOwnGeometry() {
        List<RowResult> r = rows(continued(25, 136, 325, 465), absoluteContinuation().build());
        assertEquals(2, r.size());
        assertEquals("PAGE TWO", r.get(1).description);
        assertEquals(-5.00, r.get(1).amount, 0.001);
        assertEquals(85.00, r.get(1).balance, 0.001);
    }

    @Test
    void continuationColumnsAreNotUsedOnThePageThatHasTheHeader() {
        List<RowResult> r = rows(continued(25, 136, 325, 465), absoluteContinuation().build());
        assertEquals("PAGE ONE", r.get(0).description);
        assertEquals(-10.00, r.get(0).amount, 0.001);
    }

    @Test
    void aPageThatRepeatsTheHeaderUsesTheHeaderGeometryEvenWithContinuationColumns() {
        List<Line> lines = List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"),
                bankHeader(1, 30, 0),
                bankRow(1, 40, 0, "06/01/2026", "TWO", "5.00", null, "85.00"));
        assertEquals(2, rows(lines, absoluteContinuation().build()).size());
    }

    @Test
    void firstDateRelativeContinuationColumnsFollowAUniformPageShift() {
        LayoutProfile p = bank("cont")
                .continuationFromFirstDate(Role.DATE, -10, 60).continuationFromFirstDate(Role.NARRATION, 45, 255)
                .continuationFromFirstDate(Role.DEBIT, 255, 325).continuationFromFirstDate(Role.CREDIT, 325, 395)
                .continuationFromFirstDate(Role.BALANCE, 395, 505).build();
        List<RowResult> r = rows(continued(25, 95, 305, 445), p);
        assertEquals(2, r.size());
        assertEquals(-5.00, r.get(1).amount, 0.001);
        assertEquals(85.00, r.get(1).balance, 0.001);
    }

    @Test
    void aFirstDateAnchorWithNoDateOnThePageFallsBackToThePreviousGeometry() {
        LayoutProfile p = bank("cont")
                .continuationFromFirstDate(Role.DATE, -10, 60).continuationFromFirstDate(Role.AMOUNT, 0, 10).build();
        List<Line> lines = List.of(bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "ONE", "10.00", null, "90.00"),
                ln(1, 40, "no dates here", 120));
        assertEquals(1, rows(lines, p).size());
    }

    @Test
    void continuationColumnsNeedADateColumnAndOnlyAbsoluteOrFirstDateEdges() {
        assertThrows(IllegalStateException.class, () -> bank("x").continuationFromX(Role.NARRATION, 0, 10).build());
        assertThrows(IllegalStateException.class, () -> bank("x").continuationFromX(Role.DATE, 0, 10)
                .continuationColumn(Role.NARRATION, LayoutProfile.at("DATE", 0), LayoutProfile.at("DATE", 10)).build());
    }

    // ---- D: IGNORE cells with their own amount and marker ----

    private static LayoutProfile ignoreCell() {
        return single("ign").suffix("Cr", "Dr", Direction.DEBIT).absoluteColumn(Role.IGNORE, 480, 620).build();
    }

    @Test
    void anAmountWithItsOwnMarkerInAnIgnoreColumnIsNeitherAStrayMarkerNorAnUnreadAmount() {
        var res = ProfiledReader.read(one(ln(0, 110, "05/01/2026", 50, "SHOP", 120, "100.00", 405, "Dr", 440,
                "5.00", 500, "Cr", 540)), ignoreCell());
        assertEquals(-100.00, res.outcome().results().get(0).amount, 0.001);
        assertEquals("suffix", res.outcome().results().get(0).signSource);
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void theRowMarkerIsStillTheTokenAdjacentToTheAmountColumnAmount() {
        var res = ProfiledReader.read(one(ln(0, 110, "05/01/2026", 50, "REFUND", 120, "100.00", 405, "Cr", 440,
                "5.00", 500, "Dr", 540)), ignoreCell());
        assertEquals(100.00, res.outcome().results().get(0).amount, 0.001);
        assertEquals(0, res.unreadAmountLines());
    }

    @Test
    void aMarkerOutsideEveryColumnIsStillAStrayMarker() {
        var res = ProfiledReader.read(one(ln(0, 110, "05/01/2026", 50, "SHOP", 120, "100.00", 405, "Dr", 440, "Cr", 700)),
                ignoreCell());
        assertEquals(1, res.unreadAmountLines());
    }

    // ---- E: header-geometry variants ----

    private static LayoutProfile variant(String id, double min, double max) {
        return single(id).suffix("Cr", "Dr", Direction.DEBIT).requiresLabelAt("AMT", min, max).build();
    }

    @Test
    void aProfileMatchesOnlyTheHeaderGeometryItDeclares() {
        LayoutProfile left = variant("left", 390, 410);
        LayoutProfile right = variant("right", 425, 445);
        List<Line> atLeft = List.of(singleHeader(0, 100, 0));
        List<Line> atRight = List.of(singleHeader(0, 100, 33));
        assertTrue(ProfiledReader.hasHeader(atLeft, left));
        assertFalse(ProfiledReader.hasHeader(atLeft, right));
        assertTrue(ProfiledReader.hasHeader(atRight, right));
        assertFalse(ProfiledReader.hasHeader(atRight, left));
    }

    @Test
    void theRegistryPicksTheVariantWhoseGeometryMatches() {
        LayoutRegistry reg = new LayoutRegistry(List.of(variant("left", 390, 410), variant("right", 425, 445)));
        List<Line> atRight = List.of(singleHeader(0, 100, 33),
                singleRow(0, 110, 33, "05/01/2026", "SHOP", "10.00", "Dr"));
        assertEquals("right", reg.match(atRight, (String) null).id());
        assertEquals("left", reg.match(List.of(singleHeader(0, 100, 0)), (String) null).id());
    }

    @Test
    void eachVariantReadsItsOwnArrangementWithColumnsRelativeToItsOwnLabel() {
        List<RowResult> r = rows(List.of(singleHeader(0, 100, 33),
                singleRow(0, 110, 33, "05/01/2026", "SHOP", "10.00", "Dr")), variant("right", 425, 445));
        assertEquals(-10.00, r.get(0).amount, 0.001);
    }

    @Test
    void aGeometryRequirementOnAnUnknownLabelDoesNotBuild() {
        assertThrows(IllegalStateException.class, () -> single("x").suffix("Cr", "Dr", Direction.DEBIT)
                .requiresLabelAt("NOPE", 0, 10).build());
    }
}
