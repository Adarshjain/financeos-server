package com.financeos.domain.instrument.price;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceHistoryClientTest {

    private final YahooPriceProvider yahoo = mock(YahooPriceProvider.class);
    private final PriceHistoryClient client = new PriceHistoryClient(new PriceProperties(), yahoo, new ObjectMapper());

    @Test
    void parsesYahooChartAndDropsNullCloses() {
        // 2026-01-05 and 2026-01-06 at 09:15 IST (03:45 UTC), plus a holiday with a null close
        String json = "{\"chart\":{\"result\":[{\"meta\":{\"gmtoffset\":19800},\"timestamp\":[1767579900,1767666300,1767752700],"
                + "\"indicators\":{\"quote\":[{\"close\":[100.12345,null,101.5]}]}}]}}";
        List<PriceHistoryClient.DailyClose> out = client.parseYahoo(json, ZoneId.of("Asia/Kolkata"));
        assertEquals(2, out.size());
        assertEquals(LocalDate.of(2026, 1, 5), out.get(0).date());
        assertEquals(new BigDecimal("100.1235"), out.get(0).close());
        assertEquals(LocalDate.of(2026, 1, 7), out.get(1).date());
    }

    @Test
    void yahooErrorOrEmptyBodyParsesToNothing() {
        assertTrue(client.parseYahoo("{\"chart\":{\"result\":null,\"error\":{\"code\":\"Not Found\"}}}", ZoneId.of("UTC")).isEmpty());
        assertTrue(client.parseYahoo("not json", ZoneId.of("UTC")).isEmpty());
        assertTrue(client.parseYahoo("{\"chart\":{\"result\":[{\"meta\":{\"symbol\":\"X\"}}]}}", ZoneId.of("UTC")).isEmpty());
    }

    @Test
    void parsesMfapiKeepingOnlyWindowRowsOldestFirst() {
        String json = "{\"meta\":{\"scheme_code\":120503},\"data\":["
                + "{\"date\":\"20-10-2026\",\"nav\":\"110.00000\"},"
                + "{\"date\":\"15-06-2026\",\"nav\":\"104.41530\"},"
                + "{\"date\":\"16-06-2026\",\"nav\":\"N.A.\"},"
                + "{\"date\":\"01-01-2020\",\"nav\":\"50.0\"}]}";
        List<PriceHistoryClient.DailyClose> out = client.parseMfapi(json, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 19));
        assertEquals(1, out.size());
        assertEquals(LocalDate.of(2026, 6, 15), out.get(0).date());
        assertEquals(new BigDecimal("104.4153"), out.get(0).close());
    }

    @Test
    void mfapiUnexpectedBodyParsesToNothing() {
        assertTrue(client.parseMfapi("{\"status\":\"ERROR\"}", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 1)).isEmpty());
    }

    /** Closes of 100 (adjusted) on 2026-01-05, 06, 07 and 08 at 09:15 IST, with the given splits JSON. */
    private List<PriceHistoryClient.DailyClose> parseWithSplits(String splitsJson) {
        String json = "{\"chart\":{\"result\":[{\"meta\":{\"gmtoffset\":19800},"
                + "\"timestamp\":[1767579900,1767666300,1767752700,1767839100],"
                + "\"events\":{\"splits\":" + splitsJson + "},"
                + "\"indicators\":{\"quote\":[{\"close\":[100,100,100,100]}]}}]}}";
        return client.parseYahoo(json, ZoneId.of("Asia/Kolkata"));
    }

    @Test
    void splitAfterTheLastCloseUnadjustsEveryClose() {
        // A split dated today (2026-01-09) has no close of its own in the window; Yahoo already applied it to all of them
        List<PriceHistoryClient.DailyClose> out = parseWithSplits(
                "{\"1767925500\":{\"date\":1767925500,\"numerator\":2,\"denominator\":1,\"splitRatio\":\"2:1\"}}");
        assertEquals(4, out.size());
        out.forEach(c -> assertEquals(new BigDecimal("200.0000"), c.close()));
    }

    @Test
    void oneSplitUnadjustsOnlyTheDaysBeforeIt() {
        // 5-for-1 split on 2026-01-07: closes before it are x5, the split day and after stay as they are
        List<PriceHistoryClient.DailyClose> out = parseWithSplits(
                "{\"1767752700\":{\"date\":1767752700,\"numerator\":5,\"denominator\":1,\"splitRatio\":\"5:1\"}}");
        assertEquals(new BigDecimal("500.0000"), out.get(0).close());
        assertEquals(new BigDecimal("500.0000"), out.get(1).close());
        assertEquals(new BigDecimal("100.0000"), out.get(2).close());
        assertEquals(new BigDecimal("100.0000"), out.get(3).close());
    }

    @Test
    void twoSplitsUnadjustCumulatively() {
        // 2-for-1 on 2026-01-06 and 5-for-1 on 2026-01-08: before the first x10, between them x5, after both x1
        List<PriceHistoryClient.DailyClose> out = parseWithSplits(
                "{\"1767666300\":{\"date\":1767666300,\"numerator\":2,\"denominator\":1},"
                + "\"1767839100\":{\"date\":1767839100,\"numerator\":5,\"denominator\":1}}");
        assertEquals(new BigDecimal("1000.0000"), out.get(0).close());
        assertEquals(new BigDecimal("500.0000"), out.get(1).close());
        assertEquals(new BigDecimal("500.0000"), out.get(2).close());
        assertEquals(new BigDecimal("100.0000"), out.get(3).close());
    }

    @Test
    void reverseSplitAndNoSplitsAreHandled() {
        // 1-for-10 consolidation on 2026-01-07: earlier closes shrink to a tenth
        List<PriceHistoryClient.DailyClose> out = parseWithSplits("{\"1767752700\":{\"date\":1767752700,\"numerator\":1,\"denominator\":10}}");
        assertEquals(new BigDecimal("10.0000"), out.get(0).close());
        assertEquals(new BigDecimal("100.0000"), out.get(2).close());
        assertEquals(new BigDecimal("100.0000"), parseWithSplits("{}").get(0).close());
    }

    @Test
    void yahooCoolingDownSkipsWithoutRequest() {
        when(yahoo.isRateLimited()).thenReturn(true);
        PriceHistoryClient.Result r = client.fetchYahoo("TCS.NS", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
        assertEquals(PriceHistoryClient.Status.RATE_LIMITED, r.status());
        assertTrue(r.closes().isEmpty());
    }
}
