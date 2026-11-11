package com.financeos.domain.instrument.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class PriceHistoryClientSplitsTest {

    private final PriceHistoryClient client =
            new PriceHistoryClient(new PriceProperties(), mock(YahooPriceProvider.class), new ObjectMapper());

    @Test
    void readsSplitFactorByDay() {
        // 1767579900 = 2026-01-05 09:15 IST; 1 old share becomes 5
        String json = "{\"chart\":{\"result\":[{\"meta\":{\"gmtoffset\":19800},\"timestamp\":[1767579900],"
                + "\"indicators\":{\"quote\":[{\"close\":[100.0]}]},"
                + "\"events\":{\"splits\":{\"1767579900\":{\"date\":1767579900,\"numerator\":5,\"denominator\":1,\"splitRatio\":\"5:1\"}}}}]}}";
        TreeMap<LocalDate, BigDecimal> out = client.parseYahooSplits(json, ZoneId.of("Asia/Kolkata"));
        assertEquals(1, out.size());
        assertEquals(0, out.get(LocalDate.of(2026, 1, 5)).compareTo(new BigDecimal("5")));
    }

    @Test
    void noEventsOrBadBodyIsEmpty() {
        assertTrue(client.parseYahooSplits("{\"chart\":{\"result\":[{\"meta\":{}}]}}", ZoneId.of("UTC")).isEmpty());
        assertTrue(client.parseYahooSplits("not json", ZoneId.of("UTC")).isEmpty());
    }
}
