package com.financeos.domain.investment.dividend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.instrument.price.PriceProperties;
import com.financeos.domain.instrument.price.YahooDividendEventsClient;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooDividendEventsClientTest {

    @Test
    void testParseDividendEventsJson() {
        String mockJson = """
        {
          "chart": {
            "result": [
              {
                "meta": { "symbol": "RELIANCE.NS" },
                "events": {
                  "dividends": {
                    "1723680000": {
                      "amount": 10.5,
                      "date": 1723680000
                    },
                    "1692057600": {
                      "amount": 9.0,
                      "date": 1692057600
                    }
                  }
                }
              }
            ]
          }
        }
        """;

        PriceProperties props = new PriceProperties();
        ObjectMapper mapper = new ObjectMapper();
        YahooDividendEventsClient client = new YahooDividendEventsClient(props, mapper);

        List<YahooDividendEventsClient.DividendEvent> events = client.parseDividendEventsJson(mockJson, ZoneId.of("Asia/Kolkata"));

        assertEquals(2, events.size());
        assertTrue(events.stream().anyMatch(e -> e.amount().compareTo(new BigDecimal("10.5")) == 0));
        assertTrue(events.stream().anyMatch(e -> e.amount().compareTo(new BigDecimal("9.0")) == 0));
    }
}
