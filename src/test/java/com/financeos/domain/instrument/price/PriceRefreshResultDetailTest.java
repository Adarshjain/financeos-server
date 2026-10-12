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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The PRICE_REFRESH job result names every instrument it updated or skipped, not just the counts. */
class PriceRefreshResultDetailTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 9);

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private PriceProvider provider;
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
        instrument.setSymbol("INFY");
        instrument.setYahooSymbol("INFY.NS");
        when(instrumentRepository.findById(instrument.getId())).thenReturn(Optional.of(instrument));
        provider = mock(PriceProvider.class);
        when(provider.source()).thenReturn(PriceSource.YAHOO);
        when(provider.supports(any())).thenReturn(true);
        when(provider.fetch(any())).thenReturn(Map.of(instrument.getId(), new PriceQuote(new BigDecimal("1500"), D)));
        service = new PriceRefreshService(instrumentRepository, priceRepository, mock(HoldingRepository.class),
                List.of(provider), new PriceProperties(), mock(InstrumentClassificationService.class));
    }

    @Test
    void aNewPriceIsListedWithItsCloseDateAndSource() {
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), D)).thenReturn(Optional.empty());

        PriceRefreshResult result = service.refresh(Optional.of(instrument.getId()));

        assertThat(result.refreshedItems()).containsExactly(new PriceRefreshResult.RefreshedItem(
                instrument.getId(), "Infosys (INFY)", new BigDecimal("1500"), D, PriceSource.YAHOO));
        assertThat(result.skippedItems()).isEmpty();
    }

    @Test
    void anUpdatedFeedPriceIsListedToo() {
        InstrumentPrice feed = new InstrumentPrice(instrument, D, BigDecimal.ONE, PriceSource.YAHOO);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), D)).thenReturn(Optional.of(feed));

        PriceRefreshResult result = service.refresh(Optional.of(instrument.getId()));

        assertThat(result.refreshedItems()).hasSize(1);
        assertThat(result.refreshedItems().get(0).close()).isEqualByComparingTo("1500");
        assertThat(result.refreshedItems().get(0).priceDate()).isEqualTo(D);
    }

    @Test
    void aKeptManualPriceIsNamedWithTheReason() {
        InstrumentPrice legacy = new InstrumentPrice(instrument, D, BigDecimal.ONE, PriceSource.MANUAL);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(instrument.getId(), D)).thenReturn(Optional.of(legacy));

        PriceRefreshResult result = service.refresh(Optional.of(instrument.getId()));

        assertThat(result.refreshedItems()).isEmpty();
        assertThat(result.skippedItems()).hasSize(1);
        PriceRefreshResult.SkippedItem skipped = result.skippedItems().get(0);
        assertThat(skipped.instrumentId()).isEqualTo(instrument.getId());
        assertThat(skipped.instrumentName()).isEqualTo("Infosys (INFY)");
        assertThat(skipped.reason()).contains("manually entered").contains(D.toString());
    }

    @Test
    void aFailedFetchIsNeitherRefreshedNorSkipped() {
        when(provider.fetch(any())).thenReturn(Map.of());

        PriceRefreshResult result = service.refresh(Optional.of(instrument.getId()));

        assertThat(result.failed()).hasSize(1);
        assertThat(result.refreshedItems()).isEmpty();
        assertThat(result.skippedItems()).isEmpty();
    }

    @Test
    void nothingToRefreshGivesEmptyLists() {
        HoldingRepository holdings = mock(HoldingRepository.class);
        when(holdings.findDistinctActiveInstrumentIdsHeld()).thenReturn(List.of());
        PriceRefreshService empty = new PriceRefreshService(instrumentRepository, priceRepository, holdings,
                List.of(provider), new PriceProperties(), mock(InstrumentClassificationService.class));

        PriceRefreshResult result = empty.refresh(Optional.empty());

        assertThat(result.refreshedItems()).isEmpty();
        assertThat(result.skippedItems()).isEmpty();
        assertThat(result.failed()).isEmpty();
    }
}
