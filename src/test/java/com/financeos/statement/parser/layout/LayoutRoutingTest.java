package com.financeos.statement.parser.layout;

import com.financeos.statement.StatementParserSettings;
import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ParseSnapshot;
import com.financeos.statement.parser.ParserLimits;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.StatementParseEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.financeos.statement.parser.StatementParseException;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reader routing per mode, the shadow comparison hook, forced readers, snapshot fields and the settings class. */
class LayoutRoutingTest {

    private final List<ShadowListener.Result> seen = new ArrayList<>();

    @BeforeEach
    void setUp() {
        LayoutRouting.reset();
        seen.clear();
    }

    @AfterEach
    void tearDown() {
        LayoutRouting.reset();
    }

    /** A small bank statement both readers can read: opening, rows chaining on a balance column, closing. */
    private static List<Line> statement(String firstDebit) {
        return statement(firstDebit, false);
    }

    private static List<Line> statement(String firstDebit, boolean withAccount) {
        List<Line> all = new ArrayList<>();
        if (withAccount) {
            all.add(ln(0, 30, "Account", 40, "No:", 90, "123456****7890", 120));
        }
        all.addAll(List.of(
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                ln(0, 50, "Opening", 40, "Balance:", 100, "1,000.00", 160),
                ln(0, 60, "Closing", 40, "Balance:", 100, "1,070.00", 160),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", firstDebit, null, "970.00"),
                bankRow(0, 120, 0, "06/01/2026", "SALARY", null, "100.00", "1,070.00")));
        return all;
    }

    private static LayoutProfile profile() {
        return profile(false, true);
    }

    /** The generic profile; with or without the account number summary and the period summary. */
    private static LayoutProfile profile(boolean account, boolean period) {
        LayoutProfile.Builder b = bank("test-bank");
        if (account) {
            b.summary(LayoutProfile.MetaTarget.ACCOUNT_NUMBER, "account no").type(LayoutProfile.ValueType.MASKED_NUMBER)
                    .rightOnLine().done();
        }
        if (period) {
            b.summary(LayoutProfile.MetaTarget.PERIOD, "statement\\s+period").type(LayoutProfile.ValueType.DATE_RANGE)
                    .rightOnLine().done();
        }
        return b
                .summary(LayoutProfile.MetaTarget.OPENING_BALANCE, "opening balance").rightOnLine().done()
                .summary(LayoutProfile.MetaTarget.CLOSING_BALANCE, "closing balance").rightOnLine().done()
                .summary("previous_balance", "opening balance").rightOnLine().done()
                .build();
    }

    private void register(LayoutProfile... profiles) {
        LayoutRouting.setRegistry(new LayoutRegistry(List.of(profiles)));
        LayoutRouting.setShadowListener(seen::add);
    }

    private static LayoutProfile throwing() {
        return bank("throwing").rowStrategy((table, labels, dates) -> {
            throw new IllegalStateException("boom");
        }).build();
    }

    // ---- modes ----

    @Test
    void offReadsWithV1OnlyAndDoesNotRunTheProfile() {
        register(profile());
        LayoutRouting.setMode("test-bank", ReaderMode.OFF);
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals("v1", ps.reader());
        assertNull(ps.layoutId());
        assertTrue(seen.isEmpty());
    }

    @Test
    void shadowReturnsTheV1ResultAndReportsAComparison() {
        register(profile());
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals("v1", ps.reader());
        assertNull(ps.layoutId());
        assertEquals(1, seen.size());
        assertEquals("test-bank", seen.get(0).layoutId());
        assertFalse(seen.get(0).failed());
    }

    @Test
    void shadowIsTheDefaultModeOfEveryProfile() {
        assertEquals(ReaderMode.SHADOW, LayoutRouting.modeOf("anything"));
        register(profile());
        StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals(1, seen.size());
    }

    @Test
    void activeReturnsTheProfiledResultTaggedWithItsLayout() {
        register(profile(true, true));
        LayoutRouting.setMode("test-bank", ReaderMode.ACTIVE);
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00", true), false, null);
        assertEquals("layout", ps.reader());
        assertEquals("test-bank", ps.layoutId());
        assertEquals(2, ps.transactions().size());
        assertEquals("column", ps.transactions().get(0).signSource);
        assertTrue(seen.isEmpty());
    }

    @Test
    void theDefaultModeAppliesToProfilesWithoutTheirOwn() {
        register(profile(true, true));
        LayoutRouting.setDefaultMode(ReaderMode.ACTIVE);
        assertEquals("layout", StatementParseEngine.parseLines(statement("30.00", true), false, null).reader());
        LayoutRouting.setMode("test-bank", ReaderMode.OFF);
        assertEquals("v1", StatementParseEngine.parseLines(statement("30.00"), false, null).reader());
    }

    @Test
    void aDocumentNoProfileMatchesIsReadByV1WithoutAComparison() {
        register(profile());
        List<Line> noTable = List.of(ln(0, 20, "Nothing", 40, "here", 90));
        assertThrows(RuntimeException.class, () -> StatementParseEngine.parseLines(noTable, false, null));
        assertTrue(seen.isEmpty());
    }

    // ---- shadow comparison ----

    @Test
    void identicalReadersReportIdenticalWithZeroCounts() {
        register(profile());
        StatementParseEngine.parseLines(statement("30.00"), false, null);
        ShadowListener.Result r = seen.get(0);
        assertEquals(0, r.differingRows());
        assertEquals(0, r.differingFields());
        assertTrue(r.identical(), "differing fields: " + r.differingFields());
    }

    @Test
    void differingResultsAreCountedByRowsAndFields() {
        ParsedStatement a = parseV1(statement("30.00"));
        ParsedStatement b = parseV1(statement("40.00"));
        ShadowListener.Result r = LayoutRouting.compare("x", a, b);
        assertFalse(r.identical());
        assertFalse(r.failed());
        assertTrue(r.differingRows() >= 1);
        assertTrue(r.differingFields() >= 1);
    }

    @Test
    void aDifferenceInSignSourceAloneIsNotADifference() {
        ParsedStatement a = parseV1(statement("30.00"));
        ParsedStatement b = parseV1(statement("30.00"));
        b.transactions().get(0).signSource = "something else";
        assertTrue(LayoutRouting.compare("x", a, b).identical());
    }

    @Test
    void aDifferenceInRowCountIsCounted() {
        ParsedStatement a = parseV1(statement("30.00"));
        ParsedStatement b = parseV1(statement("30.00"));
        b.transactions().remove(1);
        assertEquals(1, LayoutRouting.compare("x", a, b).differingRows());
    }

    @Test
    void aFailedProfiledReadIsReportedAsFailedNotCompared() {
        ShadowListener.Result r = LayoutRouting.compare("x", parseV1(statement("30.00")), null);
        assertTrue(r.failed());
        assertFalse(r.identical());
    }

    private static ParsedStatement parseV1(List<Line> lines) {
        LayoutRouting.setRegistry(new LayoutRegistry(List.of()));
        try {
            return StatementParseEngine.parseLines(lines, false, null);
        } finally {
            LayoutRouting.setRegistry(null);
        }
    }

    // ---- failures never leak ----

    @Test
    void anExceptionInTheProfiledReaderDuringShadowLeavesTheV1ResultUntouched() {
        ParsedStatement alone = parseV1(statement("30.00"));
        register(throwing());
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals(ParseSnapshot.json(alone), ParseSnapshot.json(ps));
        assertEquals("v1", ps.reader());
        assertEquals(1, seen.size());
        assertTrue(seen.get(0).failed());
    }

    @Test
    void anExceptionInTheProfiledReaderWhenActiveFallsBackToV1() {
        register(throwing());
        LayoutRouting.setMode("throwing", ReaderMode.ACTIVE);
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals("v1", ps.reader());
        assertEquals(2, ps.transactions().size());
    }

    @Test
    void aMisbehavingListenerNeverAffectsTheResult() {
        register(profile());
        LayoutRouting.setShadowListener(r -> {
            throw new IllegalStateException("listener failed");
        });
        assertEquals("v1", StatementParseEngine.parseLines(statement("30.00"), false, null).reader());
    }

    // ---- forced reader (corpus tool) ----

    @Test
    void forcedV1IgnoresEveryProfileEvenWhenActive() {
        register(profile());
        LayoutRouting.setMode("test-bank", ReaderMode.ACTIVE);
        LayoutRouting.setForcedReader("v1");
        assertEquals("v1", StatementParseEngine.parseLines(statement("30.00"), false, null).reader());
        assertTrue(seen.isEmpty());
    }

    @Test
    void forcedLayoutUsesTheMatchingProfileAsIfActiveEvenWhenOff() {
        register(profile());
        LayoutRouting.setMode("test-bank", ReaderMode.OFF);
        LayoutRouting.setForcedReader("layout");
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals("layout", ps.reader());
        assertEquals("test-bank", ps.layoutId());
    }

    @Test
    void forcedLayoutOnADocumentWithoutAMatchStillUsesV1() {
        LayoutRouting.setRegistry(new LayoutRegistry(List.of()));
        LayoutRouting.setForcedReader("layout");
        assertEquals("v1", StatementParseEngine.parseLines(statement("30.00"), false, null).reader());
    }

    @Test
    void forcedReaderAcceptsBlankToClearAndRejectsOtherValues() {
        LayoutRouting.setForcedReader("layout");
        LayoutRouting.setForcedReader("");
        register(profile());
        assertEquals("v1", StatementParseEngine.parseLines(statement("30.00"), false, null).reader());
        assertThrows(IllegalArgumentException.class, () -> LayoutRouting.setForcedReader("both"));
    }

    // ---- snapshot ----

    @Test
    void v1SnapshotsOmitTheReaderFields() {
        String json = ParseSnapshot.json(parseV1(statement("30.00")));
        assertFalse(json.contains("\"layoutId\""));
        assertFalse(json.contains("\"reader\""));
    }

    @Test
    void aLayoutSnapshotNamesItsReaderAndLayout() {
        register(profile(true, true));
        LayoutRouting.setMode("test-bank", ReaderMode.ACTIVE);
        var node = ParseSnapshot.of(StatementParseEngine.parseLines(statement("30.00", true), false, null));
        assertEquals("layout", node.get("reader").asText());
        assertEquals("test-bank", node.get("layoutId").asText());
    }

    @Test
    void aResultWithoutAReaderAlsoOmitsTheFields() {
        ParsedStatement ps = parseV1(statement("30.00")).withReader(null, null);
        assertFalse(ParseSnapshot.json(ps).contains("\"reader\""));
    }


    // ---- A: no listener, no shadow run ----

    @Test
    void withoutAListenerTheShadowReadIsSkippedEntirely() {
        AtomicInteger runs = new AtomicInteger();
        LayoutRouting.setRegistry(new LayoutRegistry(List.of(bank("counting").rowStrategy((t, l, d) -> {
            runs.incrementAndGet();
            return List.of();
        }).build())));
        StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals(0, runs.get());
        LayoutRouting.setShadowListener(seen::add);
        StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals(1, runs.get());
    }

    @Test
    void theComparisonIgnoresMetaBankAndNamesTheDifferingFields() {
        ParsedStatement a = parseV1(statement("30.00"));
        ParsedStatement b = parseV1(statement("30.00"));
        a.meta().bank = "One Name";
        b.meta().bank = "Another Name";
        assertTrue(LayoutRouting.compare("x", a, b).identical());
        b.meta().accountNumber = "123456****0000";
        ShadowListener.Result r = LayoutRouting.compare("x", a, b);
        assertEquals(List.of("meta.accountNumber"), r.fieldNames());
        assertEquals(1, r.differingFields());
    }

    @Test
    void anErrorInTheShadowReadIsReportedAsFailedAndTheV1ResultIsReturned() {
        ParsedStatement alone = parseV1(statement("30.00"));
        register(bank("overflow").rowStrategy((t, l, d) -> {
            throw new StackOverflowError();
        }).build());
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00"), false, null);
        assertEquals(ParseSnapshot.json(alone), ParseSnapshot.json(ps));
        assertTrue(seen.get(0).failed());
    }

    @Test
    void theSharedInputIsNotModifiedByAShadowRunAndParsingTwiceGivesTheSameResult() {
        register(profile());
        List<Line> lines = statement("30.00");
        List<Line> copy = statement("30.00");
        String first = ParseSnapshot.json(StatementParseEngine.parseLines(lines, false, null));
        String second = ParseSnapshot.json(StatementParseEngine.parseLines(lines, false, null));
        assertEquals(copy, lines);
        assertEquals(first, second);
        assertEquals(2, seen.size());
    }

    @Test
    void whenV1ThrowsDuringShadowTheExceptionPropagatesAndNothingIsReported() {
        register(profile());
        List<Line> headerOnly = List.of(bankHeader(0, 100, 0));
        assertThrows(StatementParseException.class, () -> StatementParseEngine.parseLines(headerOnly, false, null));
        assertTrue(seen.isEmpty());
    }

    @Test
    void theExplicitlyEmptyStatementIsReturnedByV1AndTheShadowFailureIsReported() {
        register(profile());
        List<Line> empty = List.of(
                ln(0, 30, "Account", 40, "No:", 90, "1234567890", 120),
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                bankHeader(0, 100, 0),
                ln(0, 110, "There", 50, "were", 90, "no", 130, "transactions", 160, "in", 230, "this", 250, "period", 280));
        ParsedStatement ps = StatementParseEngine.parseLines(empty, false, null);
        assertEquals("explicitly empty statement", ps.parseInfo().mode);
        assertEquals("v1", ps.reader());
        assertTrue(seen.get(0).failed());
    }

    // ---- C: the ACTIVE guard ----

    private void active(LayoutProfile p) {
        register(p);
        LayoutRouting.setMode(p.id(), ReaderMode.ACTIVE);
    }

    @Test
    void whenEveryGuardHoldsTheProfiledResultIsReturnedWithoutAFallback() {
        active(profile(true, true));
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00", true), false, null);
        assertEquals("layout", ps.reader());
        assertTrue(seen.isEmpty());
    }

    @Test
    void noRowsFallsBackToV1AndIsReported() {
        active(bank("test-bank").rowStrategy((t, l, d) -> List.of()).build());
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00", true), false, null);
        assertEquals("v1", ps.reader());
        assertTrue(seen.get(0).fallback());
    }

    @Test
    void anUnreadAmountLineFallsBackToV1AndIsReported() {
        active(profile(true, true));
        List<Line> lines = new ArrayList<>(statement("30.00", true));
        lines.add(ln(0, 125, "Subtotal", 50, "9.00", 330));
        ParsedStatement ps = StatementParseEngine.parseLines(lines, false, null);
        assertEquals("v1", ps.reader());
        assertTrue(seen.get(0).fallback());
    }

    @Test
    void aMissingAccountNumberFallsBackToV1AndIsReported() {
        active(profile(false, true));
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00", true), false, null);
        assertEquals("v1", ps.reader());
        assertTrue(seen.get(0).fallback());
    }

    @Test
    void aMissingPeriodEndFallsBackToV1AndIsReported() {
        active(profile(true, false));
        ParsedStatement ps = StatementParseEngine.parseLines(statement("30.00", true), false, null);
        assertEquals("v1", ps.reader());
        assertTrue(seen.get(0).fallback());
    }

    @Test
    void aVerdictBelowAutoIngestFallsBackToV1AndIsReported() {
        active(profile(true, true));
        List<Line> lines = new ArrayList<>(statement("30.00", true));
        lines.set(3, ln(0, 60, "Closing", 40, "Balance:", 100, "9,999.00", 160));
        ParsedStatement ps = StatementParseEngine.parseLines(lines, false, null);
        assertEquals("v1", ps.reader());
        assertTrue(seen.get(0).fallback());
    }

    @Test
    void forcedLayoutSkipsTheGuardSoTheCorpusSeesTheRawRead() {
        register(profile(false, false));
        LayoutRouting.setForcedReader("layout");
        assertEquals("layout", StatementParseEngine.parseLines(statement("30.00"), false, null).reader());
    }

    // ---- settings ----

    @Test
    void settingsReadDefaultAndPerProfileModesFromTheEnvironment() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.statement.layouts.default", "off")
                .withProperty("app.statement.layouts.axis-card-dtda-2024", "ACTIVE");
        new StatementParserSettings(ParserLimits.DEFAULT_MAX_PDF_PAGES, env);
        assertEquals(ReaderMode.ACTIVE, LayoutRouting.modeOf("axis-card-dtda-2024"));
        assertEquals(ReaderMode.OFF, LayoutRouting.modeOf("some-other-layout"));
    }

    @Test
    void settingsWithNothingConfiguredLeaveEveryProfileOnShadow() {
        new StatementParserSettings(ParserLimits.DEFAULT_MAX_PDF_PAGES, new MockEnvironment());
        assertEquals(ReaderMode.SHADOW, LayoutRouting.modeOf("axis-card-dtda-2024"));
    }
}
