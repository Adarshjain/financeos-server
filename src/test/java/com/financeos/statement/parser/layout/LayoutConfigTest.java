package com.financeos.statement.parser.layout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.statement.StatementLayoutShadowMetrics;
import com.financeos.statement.StatementParserSettings;
import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ParserLimits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Settings hardening, the shadow metrics component, card key completeness and the lazy issuer lookup. */
class LayoutConfigTest {

    private ListAppender<ILoggingEvent> logs;
    private Logger settingsLogger;
    private Logger metricsLogger;

    @BeforeEach
    void setUp() {
        LayoutRouting.reset();
        logs = new ListAppender<>();
        logs.start();
        settingsLogger = (Logger) LoggerFactory.getLogger(StatementParserSettings.class);
        metricsLogger = (Logger) LoggerFactory.getLogger(StatementLayoutShadowMetrics.class);
        settingsLogger.addAppender(logs);
        metricsLogger.addAppender(logs);
        metricsLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        settingsLogger.detachAppender(logs);
        metricsLogger.detachAppender(logs);
        LayoutRouting.reset();
    }

    private void settings(MockEnvironment env) {
        new StatementParserSettings(ParserLimits.DEFAULT_MAX_PDF_PAGES, env);
    }

    private long count(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).count();
    }

    // ---- G: settings ----

    @Test
    void anUnparseableModeLogsAnErrorAndCountsAsShadow() {
        settings(new MockEnvironment().withProperty("app.statement.layouts.axis-card-dtda-2024", "sometimes"));
        assertEquals(ReaderMode.SHADOW, LayoutRouting.modeOf("axis-card-dtda-2024"));
        assertEquals(1, count(Level.ERROR));
    }

    @Test
    void anUnparseableDefaultLogsAnErrorAndCountsAsShadow() {
        settings(new MockEnvironment().withProperty("app.statement.layouts.default", "bogus"));
        assertEquals(ReaderMode.SHADOW, LayoutRouting.modeOf("anything"));
        assertEquals(1, count(Level.ERROR));
    }

    @Test
    void anUnknownProfileIdLogsAWarnAndChangesNothing() {
        settings(new MockEnvironment().withProperty("app.statement.layouts.no-such-layout", "ACTIVE"));
        assertEquals(1, count(Level.WARN));
        assertEquals(0, count(Level.ERROR));
        assertEquals(ReaderMode.SHADOW, LayoutRouting.modeOf("axis-card-dtda-2024"));
    }

    @Test
    void theDefaultCanBeOffOrShadowButNeverActive() {
        settings(new MockEnvironment().withProperty("app.statement.layouts.default", "OFF"));
        assertEquals(ReaderMode.OFF, LayoutRouting.modeOf("x"));
        assertEquals(0, count(Level.ERROR));
        settings(new MockEnvironment().withProperty("app.statement.layouts.default", "active"));
        assertEquals(ReaderMode.SHADOW, LayoutRouting.modeOf("x"));
        assertEquals(1, count(Level.ERROR));
    }

    @Test
    void activeIsAcceptedPerProfileIdWithoutAnyLogLine() {
        settings(new MockEnvironment().withProperty("app.statement.layouts.axis-card-dtda-2024", "ACTIVE"));
        assertEquals(ReaderMode.ACTIVE, LayoutRouting.modeOf("axis-card-dtda-2024"));
        assertTrue(logs.list.isEmpty());
    }

    // ---- A: metrics component ----

    @Test
    void theMetricsComponentRegistersItselfAsTheShadowListener() {
        assertFalse(LayoutRouting.hasListener());
        new StatementLayoutShadowMetrics(new SimpleMeterRegistry());
        assertTrue(LayoutRouting.hasListener());
    }

    @Test
    void everyResultKindIsCountedByLayoutAndResultAndOnlyNonIdenticalOnesLog() {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        StatementLayoutShadowMetrics m = new StatementLayoutShadowMetrics(reg);
        m.onComparison(new ShadowListener.Result("lay", ShadowListener.Kind.IDENTICAL, 0, 0, List.of()));
        m.onComparison(new ShadowListener.Result("lay", ShadowListener.Kind.IDENTICAL, 0, 0, List.of()));
        m.onComparison(new ShadowListener.Result("lay", ShadowListener.Kind.DIFFERENT, 2, 1, List.of("summaryFields.credit_limit")));
        m.onComparison(new ShadowListener.Result("lay", ShadowListener.Kind.FAILED, 0, 0, List.of()));
        m.onComparison(new ShadowListener.Result("lay", ShadowListener.Kind.FALLBACK, 0, 0, List.of()));
        String name = "financeos.statement.layout.shadow";
        assertEquals(2, reg.get(name).tags("layout", "lay", "result", "identical").counter().count());
        assertEquals(1, reg.get(name).tags("layout", "lay", "result", "different").counter().count());
        assertEquals(1, reg.get(name).tags("layout", "lay", "result", "failed").counter().count());
        assertEquals(1, reg.get(name).tags("layout", "lay", "result", "fallback").counter().count());
        assertEquals(3, count(Level.INFO));
        String different = logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(s -> s.contains("different")).findFirst().orElseThrow();
        assertTrue(different.contains("lay"));
        assertTrue(different.contains("summaryFields.credit_limit"));
        assertTrue(different.contains("2 differing rows"));
    }

    // ---- F: completeness ----

    @Test
    @SuppressWarnings("unchecked")
    void theCardKeyListEqualsTheOneStatementParserStores() throws Exception {
        Field f = Class.forName("com.financeos.gmail.reconcile.StatementParser").getDeclaredField("CARD_FIELD_KEYS");
        f.setAccessible(true);
        assertEquals((List<String>) f.get(null), CardFieldKeys.KEYS);
    }

    private static LayoutProfile.Builder cardBase() {
        return LayoutProfile.builder("card")
                .product(LayoutProfile.Product.CARD)
                .headerLabel("DATE", "\\bdate\\b").headerLabel("AMT", "\\bamount\\b")
                .fromLabel(LayoutProfile.Role.DATE, "DATE", -10, 60)
                .fromLabel(LayoutProfile.Role.AMOUNT, "AMT", -20, 50)
                .sign(LayoutProfile.Sign.MINUS_IS_CREDIT);
    }

    @Test
    void aCardProfileThatIgnoresAStoredKeyDoesNotBuildAndNamesTheKey() {
        LayoutProfile.Builder b = cardBase();
        CardFieldKeys.KEYS.stream().filter(k -> !k.equals("credit_limit")).forEach(b::notPrinted);
        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("credit_limit"));
    }

    @Test
    void declaringOrMarkingEveryKeyBuilds() {
        LayoutProfile.Builder b = cardBase();
        CardFieldKeys.KEYS.stream().filter(k -> !k.equals("credit_limit")).forEach(b::notPrinted);
        b.summary("credit_limit", "credit limit").done();
        assertNotNull(b.build());
    }

    @Test
    void aBankProfileNeedsNoCardKeys() {
        assertNotNull(bankProfile());
    }

    // ---- H: lazy issuer ----

    private static final List<Line> DOC = List.of(bankHeader(0, 100, 0),
            bankRow(0, 110, 0, "05/01/2026", "SHOP", "10.00", null, "90.00"));

    @Test
    void theIssuerIsNotLookedUpWhenNoHeaderMatches() {
        AtomicInteger calls = new AtomicInteger();
        LayoutRegistry reg = new LayoutRegistry(List.of(bank("a").issuer("axis").build()));
        assertNull(reg.matchLazy(List.of(ln(0, 10, "Nothing", 40)), () -> {
            calls.incrementAndGet();
            return "axis";
        }));
        assertEquals(0, calls.get());
    }

    @Test
    void theIssuerIsNotLookedUpForProfilesWithoutOne() {
        AtomicInteger calls = new AtomicInteger();
        LayoutRegistry reg = new LayoutRegistry(List.of(bank("a").build()));
        assertNotNull(reg.matchLazy(DOC, () -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(0, calls.get());
    }

    @Test
    void theIssuerIsLookedUpOnceEvenForSeveralCandidates() {
        AtomicInteger calls = new AtomicInteger();
        LayoutRegistry reg = new LayoutRegistry(List.of(bank("a").issuer("hdfc").build(), bank("b").issuer("axis").build()));
        LayoutProfile p = reg.matchLazy(DOC, () -> {
            calls.incrementAndGet();
            return "axis";
        });
        assertEquals("b", p.id());
        assertEquals(1, calls.get());
    }
}
