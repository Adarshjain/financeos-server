package com.financeos.domain.instrument.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PreviousCloseProviderTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final YahooPriceProvider provider = new YahooPriceProvider(new PriceProperties(), new ObjectMapper());

    @Test
    void v7ReadsPreviousCloseAndMarketState() {
        String body = "{\"quoteResponse\":{\"result\":[{\"symbol\":\"INFY.NS\",\"regularMarketPrice\":1500.5,"
                + "\"regularMarketPreviousClose\":1490.25,\"marketState\":\"REGULAR\",\"regularMarketTime\":1760349598}]}}";
        PriceQuote q = provider.parseBatchQuoteResponse(body, IST).get("infy.ns");
        assertEquals(0, new BigDecimal("1490.25").compareTo(q.previousClose()));
        assertEquals("REGULAR", q.marketState());
    }

    @Test
    void v7MissingFieldsAreNull() {
        String body = "{\"quoteResponse\":{\"result\":[{\"symbol\":\"X.NS\",\"regularMarketPrice\":10}]}}";
        PriceQuote q = provider.parseBatchQuoteResponse(body, IST).get("x.ns");
        assertNotNull(q);
        assertNull(q.previousClose());
        assertNull(q.marketState());
    }

    @Test
    void v8UsesChartPreviousCloseAndMarketStateWhenPresent() throws Exception {
        String body = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":200.5,\"regularMarketTime\":1760349598,"
                + "\"chartPreviousClose\":198.0,\"previousClose\":150.0,\"marketState\":\"CLOSED\"}}]}}";
        PriceQuote q = provider.parseChartResponse(body, IST);
        assertEquals(0, new BigDecimal("198.0").compareTo(q.previousClose()));
        assertEquals("CLOSED", q.marketState());
    }

    @Test
    void v8MissingFieldsAreNullAndNoPriceIsNoQuote() throws Exception {
        PriceQuote q = provider.parseChartResponse(
                "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":200.5,\"regularMarketTime\":1760349598}}]}}", IST);
        assertNull(q.previousClose());
        assertNull(q.marketState());
        assertNull(provider.parseChartResponse("{\"chart\":{\"result\":[{\"meta\":{}}]}}", IST));
    }
}
