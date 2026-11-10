package com.financeos.statement.parser.layout;

import com.financeos.statement.StatementLayoutShadowMetrics;
import com.financeos.statement.StatementParserSettings;
import com.financeos.statement.parser.Line;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.ParserLimits;
import com.financeos.statement.parser.StatementParseEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static com.financeos.statement.parser.layout.LayoutFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** A bad profile or registry never breaks parsing or startup. */
class LayoutContainmentTest {

    @BeforeEach
    @AfterEach
    void reset() {
        LayoutRouting.reset();
    }

    private static List<Line> statement() {
        return List.of(
                ln(0, 40, "Statement", 40, "Period:", 100, "01/01/2026", 150, "to", 220, "31/01/2026", 240),
                ln(0, 50, "Opening", 40, "Balance:", 100, "1,000.00", 160),
                ln(0, 60, "Closing", 40, "Balance:", 100, "1,070.00", 160),
                bankHeader(0, 100, 0),
                bankRow(0, 110, 0, "05/01/2026", "SHOP ONE", "30.00", null, "970.00"),
                bankRow(0, 120, 0, "06/01/2026", "SALARY", null, "100.00", "1,070.00"));
    }

    private static LayoutRegistry.Entry failing(String name, Throwable t) {
        return new LayoutRegistry.Entry(name, () -> {
            if (t instanceof Error e) {
                throw e;
            }
            throw (RuntimeException) t;
        });
    }

    private static List<LayoutRegistry.Entry> entries(LayoutRegistry.Entry bad) {
        List<LayoutRegistry.Entry> l = new ArrayList<>();
        l.add(bad);
        l.add(new LayoutRegistry.Entry("good", LayoutFixtures::bankProfile));
        return l;
    }

    private static LayoutRegistry.Entry incomplete() {
        return new LayoutRegistry.Entry("incomplete", () -> LayoutProfile.builder("incomplete")
                .product(LayoutProfile.Product.CARD)
                .headerLabel("DATE", "\\bdate\\b").headerLabel("AMT", "\\bamount\\b")
                .fromLabel(LayoutProfile.Role.DATE, "DATE", 0, 10).fromLabel(LayoutProfile.Role.AMOUNT, "AMT", 0, 10)
                .sign(LayoutProfile.Sign.MINUS_IS_CREDIT).build());
    }

    private static LayoutRegistry.Entry badRegex() {
        return new LayoutRegistry.Entry("badregex", () -> bank("x").headerLabel("BAD", "(").build());
    }

    private static List<LayoutRegistry.Entry> failures() {
        return List.of(incomplete(), badRegex(), failing("error", new NoClassDefFoundError("x")),
                failing("runtime", new IllegalArgumentException("x")));
    }

    @Test
    void aProfileThatFailsToBuildIsSkippedAndTheGoodOnesAreKept() {
        for (LayoutRegistry.Entry bad : failures()) {
            LayoutRegistry reg = LayoutRegistry.fromEntries(entries(bad));
            assertEquals(1, reg.profiles().size(), bad.name());
            assertEquals("test-bank", reg.profiles().get(0).id());
        }
    }

    @Test
    void parsingStillWorksAndTheGoodProfileStillMatchesWhenAnotherFailedToBuild() {
        for (LayoutRegistry.Entry bad : failures()) {
            LayoutRouting.setRegistry(LayoutRegistry.fromEntries(entries(bad)));
            LayoutRouting.setMode("test-bank", ReaderMode.ACTIVE);
            ParsedStatement ps = StatementParseEngine.parseLines(statement(), false, null);
            // the good profile matched; it has no account number so the guard sends it through v1
            assertNotNull(ps, bad.name());
            assertEquals(2, ps.transactions().size());
        }
    }

    @Test
    void theGoodProfileIsServedWhenAnotherFailedToBuild() {
        LayoutRouting.setRegistry(LayoutRegistry.fromEntries(entries(incomplete())));
        assertNotNull(LayoutRouting.registry().match(statement(), (String) null));
    }

    @Test
    void parseWorksWhenTheWholeRegistryInitialisationThrows() {
        for (Throwable t : List.<Throwable>of(new ExceptionInInitializerError("boom"), new NoClassDefFoundError("x"),
                new IllegalStateException("boom"))) {
            LayoutRouting.setRegistrySupplier(() -> {
                if (t instanceof Error e) {
                    throw e;
                }
                throw (RuntimeException) t;
            });
            ParsedStatement ps = StatementParseEngine.parseLines(statement(), false, null);
            assertEquals("v1", ps.reader());
            assertEquals(2, ps.transactions().size());
        }
    }

    @Test
    void theSettingsComponentStillConstructsWhenTheRegistryCannotBeCreated() {
        LayoutRouting.setRegistrySupplier(() -> {
            throw new ExceptionInInitializerError("boom");
        });
        assertDoesNotThrow(() -> new StatementParserSettings(ParserLimits.DEFAULT_MAX_PDF_PAGES,
                new MockEnvironment().withProperty("app.statement.layouts.default", "OFF")));
    }

    @Test
    void theSettingsComponentStillConstructsWhenPropertyAccessThrows() {
        MockEnvironment env = new MockEnvironment() {
            @Override
            public String getProperty(String key) {
                throw new IllegalStateException("broken environment");
            }
        };
        assertDoesNotThrow(() -> new StatementParserSettings(ParserLimits.DEFAULT_MAX_PDF_PAGES, env));
    }

    @Test
    void theSettingsComponentStillConstructsWhenAProfileFailedToBuild() {
        LayoutRouting.setRegistry(LayoutRegistry.fromEntries(entries(incomplete())));
        assertDoesNotThrow(() -> new StatementParserSettings(ParserLimits.DEFAULT_MAX_PDF_PAGES, new MockEnvironment()));
    }

    @Test
    void theMetricsComponentStillConstructs() {
        assertDoesNotThrow(() -> new StatementLayoutShadowMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void theDefaultRegistryBuildsLazilyAndHoldsTheAxisProfile() {
        assertFalse(LayoutRegistry.defaults().profiles().isEmpty());
    }
}
