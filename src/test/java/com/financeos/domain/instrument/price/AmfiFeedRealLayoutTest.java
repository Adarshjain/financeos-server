package com.financeos.domain.instrument.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * NAVAll.txt as AMFI publishes it: category header, blank line, AMC-name line, blank line, the
 * schemes, a blank line, the next AMC … then the next header. AMC lines keep the category; any other
 * line without ';' is a section the parser does not know, and must not leak the previous category.
 */
class AmfiFeedRealLayoutTest {

    private static final String BANKING_PSU = "Open Ended Schemes(Debt Scheme - Banking and PSU Fund)";
    private static final String LARGE_CAP = "Open Ended Schemes ( Equity Scheme - Large Cap Fund )";

    private static final String FEED = String.join("\r\n",
            "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date",
            "",
            BANKING_PSU,
            "",
            "Aditya Birla Sun Life Mutual Fund",
            "",
            "119551;INF209KA12Z1;INF209KA13Z9;Aditya Birla Sun Life Banking & PSU Debt Fund - DIRECT - IDCW;108.1234;08-Oct-2026",
            "119552;INF209K01YO8;-;Aditya Birla Sun Life Banking & PSU Debt Fund - Direct Growth;345.6789;08-Oct-2026",
            "",
            "Axis Mutual Fund",
            "",
            "120437;INF846K01CH7;;Axis Banking & PSU Debt Fund - Direct Plan - Growth Option;2500.1000;08-Oct-2026",
            "",
            "360 ONE Mutual Fund (Formerly Known as IIFL Mutual Fund)",
            "",
            "130001;INF579M01AA1;;360 ONE Banking PSU Fund - Growth;11.2000;08-Oct-2026",
            "",
            "Exchange Traded Schemes(Gold)",
            "",
            "Nippon India Mutual Fund",
            "",
            "140088;INF204KB17I5;;Nippon India ETF Gold BeES;70.1200;08-Oct-2026",
            "",
            LARGE_CAP,
            "",
            "HDFC Mutual Fund",
            "",
            "119018;INF179K01BE2;;HDFC Top 100 Fund - Direct Growth;1150.1000;08-Oct-2026");

    private static Map<String, String> categoriesByCode() {
        AmfiFeedClient client = new AmfiFeedClient(new PriceProperties());
        client.ingest(FEED);
        Map<String, String> byCode = new HashMap<>();
        client.all().forEach(s -> byCode.put(s.schemeCode(), s.category()));
        return byCode;
    }

    @Test
    void amcLinesBetweenSchemesKeepTheHeaderAbove() {
        Map<String, String> byCode = categoriesByCode();
        assertEquals(BANKING_PSU, byCode.get("119551"));
        assertEquals(BANKING_PSU, byCode.get("119552"));
        assertEquals(BANKING_PSU, byCode.get("120437"), "after a second AMC line");
        assertEquals(BANKING_PSU, byCode.get("130001"), "an AMC name with a bracketed former name");
        assertEquals(LARGE_CAP, byCode.get("119018"), "spaced-out header");
    }

    @Test
    void anUnrecognisedHeaderClearsTheCategory() {
        Map<String, String> byCode = categoriesByCode();
        assertTrue(byCode.containsKey("140088"));
        assertNull(byCode.get("140088"), "not the Banking & PSU category of the section above");
    }

    @Test
    void aDashIsinIsNoIsin() {
        AmfiFeedClient client = new AmfiFeedClient(new PriceProperties());
        client.ingest(FEED);
        assertNull(client.getQuoteByIsin("-"));
        assertNull(client.getSchemeCategory(null, "-"));
        assertEquals(BANKING_PSU, client.getSchemeCategory(null, "INF209K01YO8"));
    }

    @Test
    void amcNameLinesAreRecognised() {
        assertTrue(AmfiFeedClient.isAmcNameLine("Aditya Birla Sun Life Mutual Fund"));
        assertTrue(AmfiFeedClient.isAmcNameLine("quant Mutual Fund"));
        assertTrue(AmfiFeedClient.isAmcNameLine("360 ONE Mutual Fund (Formerly Known as IIFL Mutual Fund)"));
        assertFalse(AmfiFeedClient.isAmcNameLine("Exchange Traded Schemes(Gold)"));
        assertFalse(AmfiFeedClient.isAmcNameLine("Open Ended Schemes(Equity Scheme - Large Cap Fund)"));
    }
}
