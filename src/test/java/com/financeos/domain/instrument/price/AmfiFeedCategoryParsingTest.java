package com.financeos.domain.instrument.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AMFI NAVAll.txt parsing remembers the scheme-category header each scheme sits under (lines
 * without ';' like "Open Ended Schemes(Equity Scheme - Large Cap Fund)"), ignores AMC-name lines,
 * and indexes the category by scheme code and both ISINs.
 */
class AmfiFeedCategoryParsingTest {

    private static final String LARGE_CAP = "Open Ended Schemes(Equity Scheme - Large Cap Fund)";
    private static final String LIQUID = "Open Ended Schemes(Debt Scheme - Liquid Fund)";
    private static final String FMP = "Close Ended Schemes(Income)";

    private static final String FEED = String.join("\r\n",
            "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date",
            "",
            "100001;INF000A01011;-;Orphan Fund - Growth;10.5000;08-Oct-2026",
            LARGE_CAP,
            "",
            "Aditya Birla Sun Life Mutual Fund",
            "",
            "119551;INF209K01YN0;INF209K01YO8;ABSL Frontline Equity Fund - Growth;512.3400;08-Oct-2026",
            "HDFC Mutual Fund",
            "119018;INF179K01BE2;;HDFC Top 100 Fund - Direct Growth;1150.1000;08-Oct-2026",
            LIQUID,
            "Nippon India Mutual Fund",
            "118701;;INF204K01UN9;Nippon India Liquid Fund - Growth;6000.0000;08-Oct-2026",
            "118702;INF204K01XX1;;Nippon India Liquid Fund - Bonus;N.A.;08-Oct-2026",
            FMP,
            "SBI Mutual Fund",
            "140001;INF200K01AA1;;SBI FMP Series 1;11.0000;07-Oct-2026");

    private AmfiFeedClient client;

    @BeforeEach
    void setUp() {
        client = new AmfiFeedClient(new PriceProperties());
        client.ingest(FEED);
    }

    @Test
    void eachSchemeCarriesTheHeaderAboveIt() {
        Map<String, String> byName = new java.util.HashMap<>();
        client.all().forEach(s -> byName.put(s.name(), s.category()));

        assertEquals(LARGE_CAP, byName.get("ABSL Frontline Equity Fund - Growth"));
        assertEquals(LARGE_CAP, byName.get("HDFC Top 100 Fund - Direct Growth"), "AMC lines do not reset the category");
        assertEquals(LIQUID, byName.get("Nippon India Liquid Fund - Growth"));
        assertEquals(FMP, byName.get("SBI FMP Series 1"));
        assertNull(byName.get("Orphan Fund - Growth"), "no header above it");
    }

    @Test
    void schemesWithoutANavAreStillSkipped() {
        assertEquals(5, client.all().size());
        assertNull(client.getSchemeCategory("118702", "INF204K01XX1"));
    }

    @Test
    void categoryIsFoundBySchemeCodeFirst() {
        assertEquals(LARGE_CAP, client.getSchemeCategory("119551", "INF204K01UN9"));
        assertEquals(LARGE_CAP, client.getSchemeCategory(" 119551 ", null));
    }

    @Test
    void categoryFallsBackToEitherIsin() {
        assertEquals(LARGE_CAP, client.getSchemeCategory(null, "INF209K01YN0"));
        assertEquals(LARGE_CAP, client.getSchemeCategory("999999", "INF209K01YO8"), "the reinvestment ISIN too");
        assertEquals(LIQUID, client.getSchemeCategory("", "INF204K01UN9"));
    }

    @Test
    void unknownOrBlankKeysHaveNoCategory() {
        assertNull(client.getSchemeCategory("999999", "INF999999999"));
        assertNull(client.getSchemeCategory(null, null));
        assertNull(client.getSchemeCategory(" ", " "));
        assertNull(client.getSchemeCategory("100001", null), "a scheme before any header has none");
    }

    @Test
    void quotesStillIndexByCodeAndIsin() {
        assertEquals(new BigDecimal("512.3400"), client.getQuoteBySchemeCode("119551").close());
        assertEquals(LocalDate.of(2026, 10, 8), client.getQuoteByIsin("INF204K01UN9").asOf());
    }

    @Test
    void parsingStaysStaticAndIndexesTheFirstSchemePerKey() {
        List<AmfiScheme> schemes = new java.util.ArrayList<>();
        Map<String, String> byCode = new java.util.HashMap<>();
        Map<String, String> byIsin = new java.util.HashMap<>();
        AmfiFeedClient.parseAmfiFeed(String.join("\n",
                        LARGE_CAP, "1;ISINA;;A;1.0;08-Oct-2026",
                        LIQUID, "1;ISINA;;A again;1.0;08-Oct-2026"),
                schemes, new java.util.HashMap<>(), new java.util.HashMap<>(), byCode, byIsin);

        assertEquals(2, schemes.size());
        assertEquals(LARGE_CAP, byCode.get("1"));
        assertEquals(LARGE_CAP, byIsin.get("ISINA"));
    }

    @Test
    void anEmptyFeedLeavesNothing() {
        client.ingest("");
        assertEquals(List.of(), client.all());
        assertNull(client.getSchemeCategory("119551", null));
    }

    @Test
    void theLegacySchemeConstructorHasNoCategory() {
        assertNull(new AmfiScheme("1", "I", "N", BigDecimal.ONE, LocalDate.of(2026, 1, 1)).category());
    }
}
