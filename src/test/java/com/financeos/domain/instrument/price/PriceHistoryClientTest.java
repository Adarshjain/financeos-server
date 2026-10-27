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

    @Test
    void yahooCoolingDownSkipsWithoutRequest() {
        when(yahoo.isRateLimited()).thenReturn(true);
        PriceHistoryClient.Result r = client.fetchYahoo("TCS.NS", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1));
        assertEquals(PriceHistoryClient.Status.RATE_LIMITED, r.status());
        assertTrue(r.closes().isEmpty());
    }
}
