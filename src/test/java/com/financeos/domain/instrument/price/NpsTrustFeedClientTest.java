package com.financeos.domain.instrument.price;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the two NPS Trust files (samples from the real feeds), including BOM, CRLF and junk lines. */
class NpsTrustFeedClientTest {

    private static final String DAILY = "ID\tDATE OF NAV\tPFM NAME\tSCHEME ID\tSCHEME NAME\tNAV VALUE\n"
            + "1\t2026-11-04\tSBI Pension Funds Pvt. Ltd.\tSM001001\tSBI PENSION FUND SCHEME - CENTRAL GOVT\t50.4900\n"
            + "2\t2026-11-04\tHDFC Pension Fund Management Limited\tSM008018\tHDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT\t53.2844\n";

    private static final String HISTORY = "ID\tDATE OF NAV\tPFM ID\tPFM NAME\tSCHEME ID\tSCHEME NAME\tNAV VALUE\n"
            + "1\t2026-11-04\tPFM008\tHDFC Pension Fund Management Limited\tSM008001\tHDFC SCHEME E - TIER I\t53.2844\n"
            + "2\t2026-11-03\tPFM008\tHDFC Pension Fund Management Limited\tSM008001\tHDFC SCHEME E - TIER I\t53.1000\n";

    @Test
    void parsesTheDailyFile() {
        List<NpsTrustFeedClient.NavRow> rows = NpsTrustFeedClient.parseDaily(DAILY);

        assertEquals(2, rows.size());
        NpsTrustFeedClient.NavRow second = rows.get(1);
        assertEquals("SM008018", second.schemeCode());
        assertEquals("HDFC PENSION FUND MANAGEMENT LIMITED SCHEME E - TIER I DIRECT", second.schemeName());
        assertEquals("HDFC Pension Fund Management Limited", second.pfmName());
        assertEquals(LocalDate.of(2026, 11, 4), second.navDate());
        assertEquals(0, new BigDecimal("53.2844").compareTo(second.nav()));
    }

    @Test
    void dailyFileToleratesBomCrlfBlankLinesAndBadRows() {
        String body = "﻿" + DAILY.replace("\n", "\r\n") + "\r\n\r\n"
                + "3\tnot-a-date\tX\tSM009001\tSome scheme\t10.0\r\n"
                + "4\t2026-11-04\tX\tSM009002\tOther scheme\tn.a.\r\n"
                + "5\t2026-11-04\tX\t\tNo code\t10.0\r\n";

        List<NpsTrustFeedClient.NavRow> rows = NpsTrustFeedClient.parseDaily(body);

        assertEquals(2, rows.size());
        assertEquals("SM001001", rows.get(0).schemeCode());
    }

    @Test
    void parsesTheHistoryFile() {
        List<PriceHistoryClient.DailyClose> closes = NpsTrustFeedClient.parseHistory("﻿" + HISTORY.replace("\n", "\r\n"), "SM008001");

        assertEquals(2, closes.size());
        assertEquals(LocalDate.of(2026, 11, 4), closes.get(0).date());
        assertEquals(0, new BigDecimal("53.2844").compareTo(closes.get(0).close()));
        assertEquals(LocalDate.of(2026, 11, 3), closes.get(1).date());
    }

    @Test
    void historyKeepsOnlyTheRequestedSchemesRows() {
        // the real SM008018 file carries its predecessor SM008001 before Apr 2026
        String mixed = "ID\tDATE OF NAV\tPFM ID\tPFM NAME\tSCHEME ID\tSCHEME NAME\tNAV VALUE\n"
                + "1\t2026-04-01\tPFM008\tHDFC\tSM008018\tHDFC SCHEME E - TIER I DIRECT\t49.5635\n"
                + "2\t2026-04-01\tPFM008\tHDFC\tSM008001\tHDFC SCHEME E - TIER I\t49.5635\n"
                + "3\t2026-03-31\tPFM008\tHDFC\tSM008001\tHDFC SCHEME E - TIER I\t49.5635\n";

        List<PriceHistoryClient.DailyClose> direct = NpsTrustFeedClient.parseHistory(mixed, "SM008018");
        List<PriceHistoryClient.DailyClose> regular = NpsTrustFeedClient.parseHistory(mixed, "SM008001");

        assertEquals(1, direct.size());
        assertEquals(LocalDate.of(2026, 4, 1), direct.get(0).date());
        assertEquals(2, regular.size());
    }

    @Test
    void emptyOrHeaderOnlyBodiesGiveNothing() {
        assertTrue(NpsTrustFeedClient.parseDaily("").isEmpty());
        assertTrue(NpsTrustFeedClient.parseDaily(null).isEmpty());
        assertTrue(NpsTrustFeedClient.parseHistory("ID\tDATE OF NAV\tPFM ID\tPFM NAME\tSCHEME ID\tSCHEME NAME\tNAV VALUE\n", "SM008001").isEmpty());
    }

    @Test
    void ingestExposesQuotesBySchemeCode() {
        NpsTrustFeedClient client = new NpsTrustFeedClient(new PriceProperties());
        client.ingest(DAILY);

        assertEquals(2, client.all().size());
        PriceQuote quote = client.getQuote("SM008018");
        assertEquals(LocalDate.of(2026, 11, 4), quote.asOf());
        assertEquals(0, new BigDecimal("53.2844").compareTo(quote.close()));
        assertEquals(null, client.getQuote("SM999999"));
    }
}
