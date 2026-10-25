package com.financeos.domain.instrument.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The quote's instant: Yahoo's last-trade time is kept, date-only feeds have none, and a refresh stores it. */
class PriceTimeTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void yahooKeepsRegularMarketTimeAndTheIstDateOfIt() {
        YahooPriceProvider provider = new YahooPriceProvider(new PriceProperties(), new ObjectMapper());
        // 2026-10-13 15:29:58 IST = 09:59:58 UTC
        long epoch = Instant.parse("2026-10-13T09:59:58Z").getEpochSecond();
        String body = "{\"quoteResponse\":{\"result\":[{\"symbol\":\"INFY.NS\",\"regularMarketPrice\":1500.5,"
                + "\"regularMarketTime\":" + epoch + "}]}}";

        PriceQuote q = provider.parseBatchQuoteResponse(body, IST).get("infy.ns");

        assertEquals(Instant.parse("2026-10-13T09:59:58Z"), q.at());
        assertEquals(LocalDate.of(2026, 10, 13), q.asOf());
        assertEquals(0, new BigDecimal("1500.5").compareTo(q.close()));
    }

    @Test
    void yahooLateNightUtcTradeIsTheNextIstDate() {
        YahooPriceProvider provider = new YahooPriceProvider(new PriceProperties(), new ObjectMapper());
        long epoch = Instant.parse("2026-10-13T20:00:00Z").getEpochSecond(); // 01:30 IST on the 14th
        String body = "{\"quoteResponse\":{\"result\":[{\"symbol\":\"X.NS\",\"regularMarketPrice\":10,"
                + "\"regularMarketTime\":" + epoch + "}]}}";

        PriceQuote q = provider.parseBatchQuoteResponse(body, IST).get("x.ns");

        assertEquals(LocalDate.of(2026, 10, 14), q.asOf());
        assertEquals(Instant.ofEpochSecond(epoch), q.at());
    }

    @Test
    void yahooWithoutATimeHasNoInstant() {
        YahooPriceProvider provider = new YahooPriceProvider(new PriceProperties(), new ObjectMapper());
        String body = "{\"quoteResponse\":{\"result\":[{\"symbol\":\"X.NS\",\"regularMarketPrice\":10}]}}";

        assertNull(provider.parseBatchQuoteResponse(body, IST).get("x.ns").at());
    }

    @Test
    void aDateOnlyQuoteHasNoTime() {
        assertNull(new PriceQuote(BigDecimal.TEN, LocalDate.of(2026, 10, 13)).at());
    }

    @Test
    void manualPricesHaveNoTime() {
        Instrument instrument = new Instrument();
        assertNull(InstrumentPrice.manual(instrument, UUID.randomUUID(), LocalDate.of(2026, 10, 13), BigDecimal.TEN).getPriceTime());
    }

    @Test
    void aRefreshStoresTheQuoteTimeOnNewAndExistingRows() {
        LocalDate d = LocalDate.of(2026, 10, 13);
        Instant at = Instant.parse("2026-10-13T09:59:58Z");
        InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
        InstrumentPriceRepository priceRepository = mock(InstrumentPriceRepository.class);
        Instrument instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.stock);
        instrument.setName("Infosys");
        instrument.setYahooSymbol("INFY.NS");
        when(instrumentRepository.findById(instrument.getId())).thenReturn(Optional.of(instrument));
        PriceProvider provider = mock(PriceProvider.class);
        when(provider.source()).thenReturn(PriceSource.YAHOO);
        when(provider.supports(any())).thenReturn(true);
        when(provider.fetch(any())).thenReturn(Map.of(instrument.getId(), new PriceQuote(new BigDecimal("1500"), d, at)));
        PriceRefreshService service = new PriceRefreshService(instrumentRepository, priceRepository, mock(HoldingRepository.class),
                List.of(provider), new PriceProperties(), mock(InstrumentClassificationService.class));

        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), d)).thenReturn(Optional.empty());
        service.refresh(Optional.of(instrument.getId()));
        ArgumentCaptor<InstrumentPrice> saved = ArgumentCaptor.forClass(InstrumentPrice.class);
        verify(priceRepository).save(saved.capture());
        assertEquals(at, saved.getValue().getPriceTime());

        InstrumentPrice existing = new InstrumentPrice(instrument, d, BigDecimal.ONE, PriceSource.YAHOO);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), d)).thenReturn(Optional.of(existing));
        service.refresh(Optional.of(instrument.getId()));
        assertEquals(at, existing.getPriceTime());
    }
}
