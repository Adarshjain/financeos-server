package com.financeos.domain.instrument.price;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

class PriceHistoryServiceTest {

    private final LocalDate today = AppTime.today();
    private PriceProperties props;
    private InstrumentRepository instruments;
    private InstrumentPriceRepository prices;
    private InvestmentTransactionRepository trades;
    private PriceHistoryClient client;
    private JdbcTemplate jdbc;
    private PriceHistoryService service;
    private UUID id;

    @BeforeEach
    void setUp() {
        props = new PriceProperties();
        instruments = mock(InstrumentRepository.class);
        prices = mock(InstrumentPriceRepository.class);
        trades = mock(InvestmentTransactionRepository.class);
        client = mock(PriceHistoryClient.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PriceHistoryService(props, instruments, prices, trades, client, jdbc);
        id = UUID.randomUUID();
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any())).thenAnswer(inv -> {
            Collection<?> rows = inv.getArgument(1);
            int[] ones = new int[rows.size()];
            Arrays.fill(ones, 1);
            return new int[][]{ones};
        });
    }

    private Instrument stock() {
        Instrument i = new Instrument();
        i.setId(id);
        i.setYahooSymbol("TCS.NS");
        return i;
    }

    private Instrument fund() {
        Instrument i = new Instrument();
        i.setId(id);
        i.setAmfiCode("120503");
        return i;
    }

    private void given(Instrument i, LocalDate firstTrade, LocalDate firstFeed) {
        when(instruments.findAllById(any())).thenReturn(List.of(i));
        List<Object[]> t = new ArrayList<>();
        if (firstTrade != null) t.add(new Object[]{id.toString(), firstTrade});
        when(trades.findEarliestTradeDates(any())).thenReturn(t);
        List<Object[]> f = new ArrayList<>();
        if (firstFeed != null) f.add(new Object[]{id.toString(), firstFeed});
        when(prices.findEarliestFeedAsOf(any())).thenReturn(f);
    }

    @Test
    void windowRecentTradeReachesBackOneYear() {
        assertEquals(today.minusYears(1), PriceHistoryService.windowStart(today, today.minusMonths(3), 2, 1));
    }

    @Test
    void windowMidTradeReachesBackToFirstTrade() {
        assertEquals(today.minusMonths(18), PriceHistoryService.windowStart(today, today.minusMonths(18), 2, 1));
    }

    @Test
    void windowOldTradeCappedAtTwoYears() {
        assertEquals(today.minusYears(2), PriceHistoryService.windowStart(today, today.minusYears(5), 2, 1));
    }

    @Test
    void windowWithoutTradeIsMinYears() {
        assertEquals(today.minusYears(1), PriceHistoryService.windowStart(today, null, 2, 1));
    }

    @Test
    void missingSpanEndSemantics() {
        LocalDate from = today.minusYears(1);
        LocalDate yesterday = today.minusDays(1);
        assertEquals(yesterday, PriceHistoryService.missingSpanEnd(from, yesterday, null));
        assertNull(PriceHistoryService.missingSpanEnd(from, yesterday, from.plusDays(3)));
        assertEquals(from.plusMonths(2).minusDays(1), PriceHistoryService.missingSpanEnd(from, yesterday, from.plusMonths(2)));
        assertEquals(yesterday, PriceHistoryService.missingSpanEnd(from, yesterday, today));
    }

    @Test
    void coveredInstrumentIsNotFetched() {
        given(stock(), today.minusYears(3), today.minusYears(2).plusDays(2));
        assertEquals(0, service.backfill(Set.of(id)));
        verifyNoInteractions(client);
    }

    @Test
    void partlyCoveredFetchesOnlyTheMissingSpan() {
        LocalDate firstFeed = today.minusMonths(6);
        given(stock(), today.minusYears(3), firstFeed);
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(
                PriceHistoryClient.Status.OK, List.of(new PriceHistoryClient.DailyClose(today.minusYears(2).plusDays(1), BigDecimal.TEN))));
        service.backfill(Set.of(id));
        verify(client).fetchYahoo("TCS.NS", today.minusYears(2), firstFeed.minusDays(1));
    }

    @Test
    void noFeedRowsFetchesFullWindowUpToYesterday() {
        given(stock(), today.minusMonths(8), null);
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.OK, List.of()));
        service.backfill(Set.of(id));
        verify(client).fetchYahoo("TCS.NS", today.minusYears(1), today.minusDays(1));
    }

    @Test
    void fundUsesMfapiWithAmfiSource() {
        given(fund(), today.minusMonths(18), null);
        when(client.fetchMutualFund(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(
                PriceHistoryClient.Status.OK, List.of(new PriceHistoryClient.DailyClose(today.minusDays(40), BigDecimal.ONE))));
        assertEquals(1, service.backfill(Set.of(id)));
        verify(client).fetchMutualFund("120503", today.minusMonths(18), today.minusDays(1));
        verify(client, never()).fetchYahoo(any(), any(), any());
        ArgumentCaptor<ParameterizedPreparedStatementSetter> setter = ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(anyString(), anyCollection(), eq(1), setter.capture());
    }

    @Test
    void instrumentWithoutSymbolOrSchemeIsSkipped() {
        Instrument manual = new Instrument();
        manual.setId(id);
        given(manual, today.minusYears(1), null);
        assertEquals(0, service.backfill(Set.of(id)));
        verifyNoInteractions(client);
    }

    @Test
    void rateLimitedSkipsQuietlyWithoutInsert() {
        given(stock(), today.minusYears(1), null);
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.RATE_LIMITED, List.of()));
        assertEquals(0, service.backfill(Set.of(id)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void clientExceptionNeverPropagates() {
        given(stock(), today.minusYears(1), null);
        when(client.fetchYahoo(anyString(), any(), any())).thenThrow(new RuntimeException("boom"));
        assertEquals(0, service.backfill(Set.of(id)));
    }

    @Test
    void existingDatesAreNotInserted() {
        given(stock(), today.minusYears(1), null);
        LocalDate d1 = today.minusDays(10), d2 = today.minusDays(11), d3 = today.minusDays(12);
        when(prices.findFeedDatesBetween(eq(id), any(), any())).thenReturn(List.of(d2));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.OK, List.of(
                new PriceHistoryClient.DailyClose(d1, BigDecimal.ONE), new PriceHistoryClient.DailyClose(d2, BigDecimal.ONE),
                new PriceHistoryClient.DailyClose(d3, BigDecimal.ONE), new PriceHistoryClient.DailyClose(today, BigDecimal.ONE))));
        assertEquals(2, service.backfill(Set.of(id)));
        ArgumentCaptor<Collection<PriceHistoryClient.DailyClose>> rows = ArgumentCaptor.forClass(Collection.class);
        verify(jdbc).batchUpdate(anyString(), rows.capture(), eq(2), any());
        List<LocalDate> dates = rows.getValue().stream().map(PriceHistoryClient.DailyClose::date).toList();
        assertEquals(List.of(d3, d1), dates);
    }

    @Test
    void duplicateKeyRaceIsIgnored() {
        given(stock(), today.minusYears(1), null);
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.OK,
                List.of(new PriceHistoryClient.DailyClose(today.minusDays(5), BigDecimal.ONE))));
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any())).thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        assertEquals(0, service.backfill(Set.of(id)));
    }

    @Test
    void killSwitchDoesNothing() {
        props.getHistory().setEnabled(false);
        assertEquals(0, service.backfill(Set.of(id)));
        verifyNoInteractions(instruments, client);
    }

    @Test
    void cleanupLoopsChunksUntilShort() {
        when(prices.deleteStaleFeedPrices(any(), anyInt())).thenReturn(PriceHistoryService.CLEANUP_CHUNK, 7);
        assertEquals(PriceHistoryService.CLEANUP_CHUNK + 7, service.cleanup());
        verify(prices, times(2)).deleteStaleFeedPrices(today.minusYears(2), PriceHistoryService.CLEANUP_CHUNK);
    }

    @Test
    void cleanupQueryKeepsNewestPerInstrumentAndNeverTouchesUserOrManualRows() throws Exception {
        Method m = InstrumentPriceRepository.class.getMethod("deleteStaleFeedPrices", LocalDate.class, int.class);
        String sql = m.getAnnotation(Query.class).value();
        assertTrue(sql.startsWith("DELETE FROM instrument_prices"));
        assertTrue(sql.contains("p.user_id IS NULL"));
        assertTrue(sql.contains("p.source <> 'MANUAL'"));
        assertTrue(sql.contains("p.as_of < :cutoff"));
        // a newer feed row must exist for the same instrument, so the newest one survives
        assertTrue(sql.contains("n.instrument_id = p.instrument_id") && sql.contains("n.as_of > p.as_of"));
    }
}
