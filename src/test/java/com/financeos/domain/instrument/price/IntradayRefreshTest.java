package com.financeos.domain.instrument.price;

import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IntradayRefreshTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    // Tuesday 2026-10-20
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private HoldingRepository holdingRepository;
    private PriceProvider yahoo;
    private PriceProvider amfi;
    private Instrument stock;
    private Instrument fund;
    private PriceRefreshService service;

    private static void clockAt(String istDateTime) {
        AppTime.useClock(Clock.fixed(LocalDateTime.parse(istDateTime).atZone(IST).toInstant(), IST));
    }

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        holdingRepository = mock(HoldingRepository.class);
        stock = new Instrument();
        stock.setId(UUID.randomUUID());
        stock.setType(InstrumentType.stock);
        stock.setName("Infosys");
        fund = new Instrument();
        fund.setId(UUID.randomUUID());
        fund.setType(InstrumentType.mutual_fund);
        fund.setName("Fund");
        yahoo = mock(PriceProvider.class);
        when(yahoo.source()).thenReturn(PriceSource.YAHOO);
        // live runs call fetchLive; a plain mock does not run the interface default, so route it to fetch
        when(yahoo.fetchLive(any())).thenAnswer(i -> yahoo.fetch(i.getArgument(0)));
        when(yahoo.supports(any())).thenAnswer(i -> ((Instrument) i.getArgument(0)).getType() == InstrumentType.stock);
        amfi = mock(PriceProvider.class);
        when(amfi.source()).thenReturn(PriceSource.AMFI);
        when(amfi.supports(any())).thenAnswer(i -> ((Instrument) i.getArgument(0)).getType() == InstrumentType.mutual_fund);
        when(holdingRepository.findDistinctActiveInstrumentIdsHeld()).thenReturn(List.of(stock.getId(), fund.getId()));
        when(instrumentRepository.findAllById(any())).thenReturn(List.of(stock, fund));
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(any(), any())).thenReturn(Optional.empty());
        service = new PriceRefreshService(instrumentRepository, priceRepository, holdingRepository,
                List.of(yahoo, amfi), new PriceProperties(), mock(InstrumentClassificationService.class));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private PriceQuote quote(String state, String tradeTimeUtc) {
        return new PriceQuote(new BigDecimal("1500"), TODAY, tradeTimeUtc == null ? null : Instant.parse(tradeTimeUtc),
                new BigDecimal("1490"), state);
    }

    @Test
    void windowIsMonToFriFrom0915Until1535() {
        assertFalse(PriceRefreshService.inIntradayWindow(LocalDateTime.parse("2026-10-20T09:14:59")));
        assertTrue(PriceRefreshService.inIntradayWindow(LocalDateTime.parse("2026-10-20T09:15:00")));
        assertTrue(PriceRefreshService.inIntradayWindow(LocalDateTime.parse("2026-10-20T15:34:59")));
        assertFalse(PriceRefreshService.inIntradayWindow(LocalDateTime.parse("2026-10-20T15:35:00")));
        assertFalse(PriceRefreshService.inIntradayWindow(LocalDateTime.parse("2026-10-24T11:00:00"))); // Saturday
        assertFalse(PriceRefreshService.inIntradayWindow(LocalDateTime.parse("2026-10-25T11:00:00"))); // Sunday
    }

    @Test
    void outsideTheWindowNothingIsFetched() {
        clockAt("2026-10-20T09:14:00");
        assertEquals(0, service.refreshIntraday());
        clockAt("2026-10-24T11:00:00");
        assertEquals(0, service.refreshIntraday());
        verify(yahoo, never()).fetch(any());
        verifyNoInteractions(holdingRepository);
    }

    @Test
    void regularQuoteIsWrittenWithPreviousCloseForTodayInOneBatchOfYahooInstrumentsOnly() {
        clockAt("2026-10-20T10:00:00");
        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), quote("REGULAR", "2026-10-20T04:25:00Z")));

        assertEquals(1, service.refreshIntraday());

        ArgumentCaptor<List<Instrument>> fetched = ArgumentCaptor.forClass(List.class);
        verify(yahoo, times(1)).fetch(fetched.capture());
        assertEquals(List.of(stock), fetched.getValue());
        verify(amfi, never()).fetch(any());
        ArgumentCaptor<InstrumentPrice> saved = ArgumentCaptor.forClass(InstrumentPrice.class);
        verify(priceRepository).save(saved.capture());
        assertEquals(TODAY, saved.getValue().getAsOf());
        assertEquals(PriceSource.YAHOO, saved.getValue().getSource());
        assertEquals(0, new BigDecimal("1490").compareTo(saved.getValue().getPreviousClose()));
        assertNull(saved.getValue().getUserId());
    }

    @Test
    void closedMarketStateIsSkipped() {
        clockAt("2026-10-20T10:00:00");
        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), quote("CLOSED", "2026-10-20T04:25:00Z")));
        assertEquals(0, service.refreshIntraday());
        verify(priceRepository, never()).save(any());
    }

    @Test
    void nullStateWithYesterdaysTimeIsSkippedAndWithTodaysTimeIsWritten() {
        clockAt("2026-10-20T10:00:00");
        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), quote(null, "2026-10-19T10:00:00Z")));
        assertEquals(0, service.refreshIntraday());
        verify(priceRepository, never()).save(any());

        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), quote(null, "2026-10-20T04:25:00Z")));
        assertEquals(1, service.refreshIntraday());
        verify(priceRepository, times(1)).save(any());
    }

    @Test
    void aManualRowOfTodayWins() {
        clockAt("2026-10-20T10:00:00");
        InstrumentPrice manual = new InstrumentPrice(stock, TODAY, new BigDecimal("1"), PriceSource.MANUAL);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(stock.getId(), TODAY)).thenReturn(Optional.of(manual));
        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), quote("REGULAR", "2026-10-20T04:25:00Z")));
        assertEquals(0, service.refreshIntraday());
        verify(priceRepository, never()).save(any());
        assertEquals(PriceSource.MANUAL, manual.getSource());
    }

    @Test
    void anExistingFeedRowIsUpdated() {
        clockAt("2026-10-20T10:00:00");
        InstrumentPrice feed = new InstrumentPrice(stock, TODAY, new BigDecimal("1"), PriceSource.YAHOO);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(stock.getId(), TODAY)).thenReturn(Optional.of(feed));
        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), quote("REGULAR", "2026-10-20T04:25:00Z")));
        assertEquals(1, service.refreshIntraday());
        assertEquals(0, new BigDecimal("1500").compareTo(feed.getClose()));
        assertEquals(0, new BigDecimal("1490").compareTo(feed.getPreviousClose()));
    }

    @Test
    void aProviderExceptionPropagatesFromTheServiceButNotFromTheScheduler() {
        clockAt("2026-10-20T10:00:00");
        when(yahoo.fetch(any())).thenThrow(new RuntimeException("boom"));
        assertThrows(RuntimeException.class, service::refreshIntraday);
        PriceSchedulerConfiguration scheduler = new PriceSchedulerConfiguration(
                mock(com.financeos.domain.job.JobService.class), service, mock(AmfiFeedClient.class));
        assertDoesNotThrow(scheduler::runIntradayPriceRefresh);
    }

    @Test
    void amfiRefreshFetchesOnlyAmfiInstruments() {
        when(amfi.fetch(any())).thenReturn(Map.of(fund.getId(),
                new PriceQuote(new BigDecimal("55"), TODAY)));
        assertEquals(1, service.refreshSource(PriceSource.AMFI));
        ArgumentCaptor<List<Instrument>> fetched = ArgumentCaptor.forClass(List.class);
        verify(amfi).fetch(fetched.capture());
        assertEquals(List.of(fund), fetched.getValue());
        verify(yahoo, never()).fetch(any());
        ArgumentCaptor<InstrumentPrice> saved = ArgumentCaptor.forClass(InstrumentPrice.class);
        verify(priceRepository).save(saved.capture());
        assertEquals(PriceSource.AMFI, saved.getValue().getSource());
        assertNull(saved.getValue().getPreviousClose());
    }

    @Test
    void amfiCronsNeverThrowAndLoadTheFeedFirst() {
        AmfiFeedClient feed = mock(AmfiFeedClient.class);
        when(amfi.fetch(any())).thenThrow(new RuntimeException("boom"));
        PriceSchedulerConfiguration scheduler = new PriceSchedulerConfiguration(
                mock(com.financeos.domain.job.JobService.class), service, feed);
        assertDoesNotThrow(scheduler::runAmfiRefresh);
        assertDoesNotThrow(scheduler::runAmfiRetry);
        verify(feed, times(2)).load();
        verify(yahoo, never()).fetch(any());
    }

    @Test
    void aRegularQuoteWithYesterdaysTimeIsSkipped() {
        clockAt("2026-10-20T10:00:00");
        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), quote("REGULAR", "2026-10-19T10:00:00Z")));
        assertEquals(0, service.refreshIntraday());
        verify(priceRepository, never()).save(any());
    }

    @Test
    void aRefreshWithoutAPreviousCloseKeepsTheStoredOne() {
        LocalDate d = LocalDate.of(2026, 10, 13);
        InstrumentPrice existing = new InstrumentPrice(stock, d, BigDecimal.ONE, PriceSource.YAHOO);
        existing.setPreviousClose(new BigDecimal("77"));
        when(instrumentRepository.findById(stock.getId())).thenReturn(Optional.of(stock));
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(stock.getId(), d)).thenReturn(Optional.of(existing));
        when(yahoo.fetch(any())).thenReturn(Map.of(stock.getId(), new PriceQuote(new BigDecimal("5"), d)));
        service.refresh(Optional.of(stock.getId()));
        assertEquals(0, new BigDecimal("5").compareTo(existing.getClose()));
        assertEquals(0, new BigDecimal("77").compareTo(existing.getPreviousClose()));
    }

    @Test
    void theFetchRunsOutsideAnyTransactionAndTheWritesInOne() {
        clockAt("2026-10-20T10:00:00");
        var tm = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var tx = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(tm.getTransaction(any())).thenReturn(tx);
        PriceRefreshService txService = new PriceRefreshService(instrumentRepository, priceRepository, holdingRepository,
                List.of(yahoo, amfi), new PriceProperties(), mock(InstrumentClassificationService.class), tm);
        boolean[] activeDuringFetch = {true};
        when(yahoo.fetch(any())).thenAnswer(i -> {
            activeDuringFetch[0] = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            verify(tm, never()).getTransaction(any());
            return Map.of(stock.getId(), quote("REGULAR", "2026-10-20T04:25:00Z"));
        });
        assertEquals(1, txService.refreshIntraday());
        assertFalse(activeDuringFetch[0]);
        verify(tm, times(1)).getTransaction(any());
        verify(tm, times(1)).commit(tx);
    }
}
