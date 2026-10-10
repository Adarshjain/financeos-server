package com.financeos.domain.instrument.price;

import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A price refresh writes only shared feed rows: a user's own MANUAL price is never read, overwritten or deleted. */
class PriceRefreshUserManualTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 9);

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private PriceRefreshService service;
    private Instrument instrument;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.stock);
        instrument.setName("Infosys");
        instrument.setYahooSymbol("INFY.NS");
        when(instrumentRepository.findById(instrument.getId())).thenReturn(Optional.of(instrument));
        PriceProvider provider = mock(PriceProvider.class);
        when(provider.source()).thenReturn(PriceSource.YAHOO);
        when(provider.supports(any())).thenReturn(true);
        when(provider.fetch(any())).thenReturn(Map.of(instrument.getId(), new PriceQuote(new BigDecimal("1500"), D)));
        service = new PriceRefreshService(instrumentRepository, priceRepository, mock(HoldingRepository.class),
                List.of(provider), new PriceProperties(), mock(InstrumentClassificationService.class));
    }

    @Test
    void aNewQuoteIsASharedRowAlongsideAnyUsersOwnPrice() {
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), D)).thenReturn(Optional.empty());

        PriceRefreshResult result = service.refresh(Optional.of(instrument.getId()));

        ArgumentCaptor<InstrumentPrice> saved = ArgumentCaptor.forClass(InstrumentPrice.class);
        verify(priceRepository).save(saved.capture());
        assertNull(saved.getValue().getUserId(), "a feed price belongs to nobody");
        assertEquals(PriceSource.YAHOO, saved.getValue().getSource());
        assertEquals(1, result.refreshed());
        verify(priceRepository, never()).findByInstrumentIdAndAsOfAndUserId(any(), any(), any());
        verify(priceRepository, never()).delete(any());
    }

    @Test
    void anExistingFeedRowIsUpdatedInPlace() {
        InstrumentPrice feed = new InstrumentPrice(instrument, D, BigDecimal.ONE, PriceSource.YAHOO);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), D)).thenReturn(Optional.of(feed));

        service.refresh(Optional.of(instrument.getId()));

        assertEquals(0, new BigDecimal("1500").compareTo(feed.getClose()));
        verify(priceRepository).save(feed);
    }

    @Test
    void aLegacySharedManualRowIsLeftAlone() {
        InstrumentPrice legacy = new InstrumentPrice(instrument, D, BigDecimal.ONE, PriceSource.MANUAL);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), D)).thenReturn(Optional.of(legacy));

        PriceRefreshResult result = service.refresh(Optional.of(instrument.getId()));

        assertEquals(0, BigDecimal.ONE.compareTo(legacy.getClose()));
        assertEquals(1, result.skipped());
        verify(priceRepository, never()).save(any());
    }
}
