package com.financeos.domain.instrument.price;

import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The not_trading mark set, cleared and honoured by the bulk refresh. */
class PriceStatusRefreshTest {

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private HoldingRepository holdingRepository;
    private PriceProvider yahoo;
    private PriceRefreshService service;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        yahoo = mock(PriceProvider.class);
        when(yahoo.source()).thenReturn(PriceSource.YAHOO);
        when(yahoo.supports(any())).thenReturn(true);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(any(), any())).thenReturn(Optional.empty());
        service = new PriceRefreshService(instrumentRepository, priceRepository, holdingRepository, List.of(yahoo),
                new PriceProperties(), mock(InstrumentClassificationService.class));
    }

    private Instrument stock(String status, Instant statusAt) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName("Acme");
        i.setYahooSymbol("ACME.NS");
        i.setPriceStatus(status);
        i.setPriceStatusAt(statusAt);
        return i;
    }

    private void held(Instrument... instruments) {
        when(holdingRepository.findDistinctActiveInstrumentIdsHeld()).thenReturn(java.util.Arrays.stream(instruments).map(Instrument::getId).toList());
        when(instrumentRepository.findAllById(any())).thenReturn(List.of(instruments));
    }

    private static PriceQuote quote() {
        return new PriceQuote(new BigDecimal("10"), LocalDate.of(2026, 10, 20));
    }

    @Test
    void anAllHostsNotFoundInstrumentIsMarkedOnceWithOneUpdate() {
        Instrument a = stock(null, null);
        Instrument b = stock(null, null);
        held(a, b);
        when(yahoo.fetch(any())).thenReturn(Map.of());
        when(yahoo.takeNotFound()).thenReturn(Set.of(a.getId()));

        service.refresh(Optional.empty());

        ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(instrumentRepository).markNotTrading(ids.capture(), any(Instant.class));
        assertEquals(List.of(a.getId()), List.copyOf(ids.getValue()));
        assertEquals("not_trading", a.getPriceStatus());
        assertNull(b.getPriceStatus());
        verify(instrumentRepository, never()).clearPriceStatus(anyCollection());
    }

    @Test
    void noNotFoundMeansNoMark() {
        Instrument a = stock(null, null);
        held(a);
        when(yahoo.fetch(any())).thenReturn(Map.of());
        when(yahoo.takeNotFound()).thenReturn(Set.of());

        service.refresh(Optional.empty());

        verify(instrumentRepository, never()).markNotTrading(anyCollection(), any());
        assertNull(a.getPriceStatus());
    }

    @Test
    void aLaterPriceClearsTheMark() {
        Instrument a = stock("not_trading", Instant.now().minus(8, ChronoUnit.DAYS));
        held(a);
        when(yahoo.fetch(any())).thenReturn(Map.of(a.getId(), quote()));

        service.refresh(Optional.empty());

        ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(instrumentRepository).clearPriceStatus(ids.capture());
        assertEquals(List.of(a.getId()), List.copyOf(ids.getValue()));
        assertNull(a.getPriceStatus());
        assertNull(a.getPriceStatusAt());
    }

    @Test
    void aFreshNotTradingInstrumentIsSkippedAndAWeekOldOneRechecked() {
        Instrument fresh = stock("not_trading", Instant.now().minus(2, ChronoUnit.DAYS));
        Instrument old = stock("not_trading", Instant.now().minus(8, ChronoUnit.DAYS));
        Instrument normal = stock(null, null);
        held(fresh, old, normal);
        when(yahoo.fetch(any())).thenReturn(Map.of());

        service.refresh(Optional.empty());

        ArgumentCaptor<List<Instrument>> fetched = ArgumentCaptor.forClass(List.class);
        verify(yahoo).fetch(fetched.capture());
        assertEquals(List.of(old, normal), fetched.getValue());
    }

    @Test
    void aRepeatNotFoundOfTheRecheckedInstrumentBumpsTheTime() {
        Instant eightDaysAgo = Instant.now().minus(8, ChronoUnit.DAYS);
        Instrument old = stock("not_trading", eightDaysAgo);
        held(old);
        when(yahoo.fetch(any())).thenReturn(Map.of());
        when(yahoo.takeNotFound()).thenReturn(Set.of(old.getId()));

        service.refresh(Optional.empty());

        verify(instrumentRepository).markNotTrading(anyCollection(), any(Instant.class));
        assertEquals(true, old.getPriceStatusAt().isAfter(eightDaysAgo));
    }

    @Test
    void anExplicitSingleInstrumentRefreshStillLooksUpAFreshNotTradingOne() {
        Instrument fresh = stock("not_trading", Instant.now().minus(1, ChronoUnit.DAYS));
        when(instrumentRepository.findById(fresh.getId())).thenReturn(Optional.of(fresh));
        when(yahoo.fetch(any())).thenReturn(Map.of());

        service.refresh(Optional.of(fresh.getId()));

        verify(yahoo).fetch(List.of(fresh));
    }

    @Test
    void theIntradayFeedSkipsAFreshNotTradingInstrument() {
        Instrument fresh = stock("not_trading", Instant.now().minus(2, ChronoUnit.DAYS));
        held(fresh);

        assertEquals(0, service.refreshSource(PriceSource.YAHOO));

        verify(yahoo, never()).fetch(any());
    }
}
