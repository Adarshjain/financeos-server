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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    /** A clock the test can move. */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-27T10:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final LocalDate today = AppTime.today();
    private PriceProperties props;
    private InstrumentRepository instruments;
    private InstrumentPriceRepository prices;
    private InvestmentTransactionRepository trades;
    private PriceHistoryClient client;
    private JdbcTemplate jdbc;
    private PriceHistoryService service;
    private UUID id;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        props = new PriceProperties();
        instruments = mock(InstrumentRepository.class);
        prices = mock(InstrumentPriceRepository.class);
        trades = mock(InvestmentTransactionRepository.class);
        client = mock(PriceHistoryClient.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PriceHistoryService(props, instruments, prices, trades, client, jdbc);
        service.setClock(clock);
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

    private void given(Instrument i, LocalDate firstTrade) {
        when(instruments.findAllById(any())).thenReturn(List.of(i));
        List<Object[]> t = new ArrayList<>();
        if (firstTrade != null) t.add(new Object[]{id.toString(), firstTrade});
        when(trades.findEarliestTradeDates(any())).thenReturn(t);
    }

    private static PriceHistoryClient.Result ok(PriceHistoryClient.DailyClose... closes) {
        return new PriceHistoryClient.Result(PriceHistoryClient.Status.OK, List.of(closes));
    }

    @Test
    void windowWithRecentTradeReachesBackTwoYearsFromToday() {
        // trade 6 months old: 6 months - 1 year is later than today - 2 years, so today - 2 years wins
        assertEquals(today.minusYears(2), PriceHistoryService.windowStart(today, today.minusMonths(6), 2, 1));
    }

    @Test
    void windowWithOldTradeReachesBackOneYearBeforeIt() {
        // trade 3 years old: start = 4 years ago, no upper cap
        assertEquals(today.minusYears(4), PriceHistoryService.windowStart(today, today.minusYears(3), 2, 1));
        assertEquals(today.minusYears(11), PriceHistoryService.windowStart(today, today.minusYears(10), 2, 1));
    }

    @Test
    void windowWithoutTradeIsMinYears() {
        assertEquals(today.minusYears(2), PriceHistoryService.windowStart(today, null, 2, 1));
    }

    @Test
    void fetchesTheWholeWindowUpToYesterdayWithRecentTrade() {
        given(stock(), today.minusMonths(6));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(ok());
        service.backfill(Set.of(id));
        verify(client).fetchYahoo("TCS.NS", today.minusYears(2), today.minusDays(1));
    }

    @Test
    void fetchesTheWholeWindowEvenWhenFeedRowsAlreadyExist() {
        given(stock(), today.minusYears(3));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(ok());
        service.backfill(Set.of(id));
        verify(client).fetchYahoo("TCS.NS", today.minusYears(4), today.minusDays(1));
        verifyNoInteractions(prices);
    }

    @Test
    void editingTheTradeEarlierRefetchesTheLongerWindow() {
        given(stock(), today.minusYears(3));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(ok());
        service.backfill(Set.of(id));
        // the trade is edited to 3.5 years ago: the new start is earlier, so the debounce does not apply
        given(stock(), today.minusYears(3).minusMonths(6));
        service.backfill(Set.of(id));
        verify(client).fetchYahoo("TCS.NS", today.minusYears(4), today.minusDays(1));
        verify(client).fetchYahoo("TCS.NS", today.minusYears(4).minusMonths(6), today.minusDays(1));
    }

    @Test
    void sameStartWithinSixHoursIsDebounced() {
        given(stock(), today.minusYears(3));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(ok());
        service.backfill(Set.of(id));
        service.backfill(Set.of(id));
        // a later start (the trade moved later) is also covered by the earlier fetch
        given(stock(), today.minusYears(2));
        service.backfill(Set.of(id));
        verify(client, times(1)).fetchYahoo(anyString(), any(), any());
    }

    @Test
    void fetchesAgainAfterSixHours() {
        given(stock(), today.minusYears(3));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(ok());
        service.backfill(Set.of(id));
        clock.advance(Duration.ofHours(6).plusMinutes(1));
        service.backfill(Set.of(id));
        verify(client, times(2)).fetchYahoo(anyString(), any(), any());
    }

    @Test
    void aFailedFetchIsNotDebounced() {
        given(stock(), today.minusYears(3));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(
                new PriceHistoryClient.Result(PriceHistoryClient.Status.FAILED, List.of()), ok());
        service.backfill(Set.of(id));
        service.backfill(Set.of(id));
        verify(client, times(2)).fetchYahoo(anyString(), any(), any());
    }

    @Test
    void fundUsesMfapiWithAmfiSource() {
        given(fund(), today.minusMonths(18));
        when(client.fetchMutualFund(anyString(), any(), any())).thenReturn(ok(new PriceHistoryClient.DailyClose(today.minusDays(40), BigDecimal.ONE)));
        assertEquals(1, service.backfill(Set.of(id)));
        verify(client).fetchMutualFund("120503", today.minusMonths(18).minusYears(1), today.minusDays(1));
        verify(client, never()).fetchYahoo(any(), any(), any());
        ArgumentCaptor<ParameterizedPreparedStatementSetter> setter = ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(anyString(), anyCollection(), eq(1), setter.capture());
    }

    @Test
    void instrumentWithoutSymbolOrSchemeIsSkipped() {
        Instrument manual = new Instrument();
        manual.setId(id);
        given(manual, today.minusYears(1));
        assertEquals(0, service.backfill(Set.of(id)));
        verifyNoInteractions(client);
    }

    @Test
    void rateLimitedSkipsQuietlyWithoutWrite() {
        given(stock(), today.minusYears(1));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(new PriceHistoryClient.Result(PriceHistoryClient.Status.RATE_LIMITED, List.of()));
        assertEquals(0, service.backfill(Set.of(id)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void clientExceptionNeverPropagates() {
        given(stock(), today.minusYears(1));
        when(client.fetchYahoo(anyString(), any(), any())).thenThrow(new RuntimeException("boom"));
        assertEquals(0, service.backfill(Set.of(id)));
    }

    @Test
    void upsertWritesFetchedRowsInWindowOnlyAndNeverToday() {
        given(stock(), today.minusYears(1));
        LocalDate d1 = today.minusDays(10), d2 = today.minusDays(11);
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(ok(
                new PriceHistoryClient.DailyClose(d1, BigDecimal.ONE), new PriceHistoryClient.DailyClose(d2, BigDecimal.ONE),
                new PriceHistoryClient.DailyClose(d2, BigDecimal.TEN), new PriceHistoryClient.DailyClose(today, BigDecimal.ONE),
                new PriceHistoryClient.DailyClose(today.minusYears(5), BigDecimal.ONE)));
        assertEquals(2, service.backfill(Set.of(id)));
        ArgumentCaptor<Collection<PriceHistoryClient.DailyClose>> rows = ArgumentCaptor.forClass(Collection.class);
        verify(jdbc).batchUpdate(anyString(), rows.capture(), eq(2), any());
        assertEquals(List.of(d2, d1), rows.getValue().stream().map(PriceHistoryClient.DailyClose::date).toList());
    }

    @Test
    void upsertSqlOverwritesFeedRowsAndNeverTouchesManualOrUserRows() throws Exception {
        java.lang.reflect.Field f = PriceHistoryService.class.getDeclaredField("UPSERT_SQL");
        f.setAccessible(true);
        String sql = (String) f.get(null);
        assertTrue(sql.startsWith("MERGE INTO instrument_prices"));
        assertTrue(sql.contains("t.user_id IS NULL"));
        assertTrue(sql.contains("UPDATE SET t.close = s.close, t.source = s.source, t.price_time = NULL"));
        assertTrue(sql.contains("WHERE t.source <> 'MANUAL'"));
        assertTrue(sql.contains("WHEN NOT MATCHED THEN INSERT"));
    }

    @Test
    void upsertBatchesBindFetchedCloseAndSource() throws Exception {
        List<PriceHistoryClient.DailyClose> closes = List.of(new PriceHistoryClient.DailyClose(today.minusDays(3), new BigDecimal("12.3400")));
        ArgumentCaptor<ParameterizedPreparedStatementSetter<PriceHistoryClient.DailyClose>> setter = ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        service.upsert(id, PriceSource.AMFI, closes);
        verify(jdbc).batchUpdate(anyString(), eq(closes), eq(1), setter.capture());
        java.sql.PreparedStatement ps = mock(java.sql.PreparedStatement.class);
        setter.getValue().setValues(ps, closes.get(0));
        verify(ps).setString(1, id.toString());
        verify(ps).setBigDecimal(3, new BigDecimal("12.3400"));
        verify(ps).setString(4, "AMFI");
    }

    @Test
    void duplicateKeyRaceIsIgnored() {
        given(stock(), today.minusYears(1));
        when(client.fetchYahoo(anyString(), any(), any())).thenReturn(ok(new PriceHistoryClient.DailyClose(today.minusDays(5), BigDecimal.ONE)));
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
    void cleanupPrunesTradedInstrumentsThenUntradedOnesInChunks() {
        when(prices.deleteFeedPricesBeforeWindow(any(), anyInt(), anyInt())).thenReturn(PriceHistoryService.CLEANUP_CHUNK, 7);
        when(prices.deleteFeedPricesOfUntradedInstruments(anyInt())).thenReturn(3);
        assertEquals(PriceHistoryService.CLEANUP_CHUNK + 7 + 3, service.cleanup());
        verify(prices, times(2)).deleteFeedPricesBeforeWindow(today.minusYears(2), 12, PriceHistoryService.CLEANUP_CHUNK);
        verify(prices, times(1)).deleteFeedPricesOfUntradedInstruments(PriceHistoryService.CLEANUP_CHUNK);
    }

    @Test
    void cleanupForTradedInstrumentsUsesWindowStartKeepsNewestAndNeverTouchesUserOrManualRows() throws Exception {
        Method m = InstrumentPriceRepository.class.getMethod("deleteFeedPricesBeforeWindow", LocalDate.class, int.class, int.class);
        String sql = m.getAnnotation(Query.class).value();
        assertTrue(sql.startsWith("DELETE FROM instrument_prices"));
        assertTrue(sql.contains("p.user_id IS NULL") && sql.contains("p.source <> 'MANUAL'"));
        // LEAST(earliest trade - years-before, today - min years); a NULL (no trade) deletes nothing here
        assertTrue(sql.contains("LEAST(ADD_MONTHS(MIN(t.trade_date), -:months), :minStart)"));
        assertTrue(sql.contains("p.as_of < (SELECT"));
        // a newer feed row must exist for the same instrument, so the newest one survives
        assertTrue(sql.contains("n.instrument_id = p.instrument_id") && sql.contains("n.as_of > p.as_of"));
    }

    @Test
    void cleanupForUntradedInstrumentsKeepsOnlyTheNewestFeedRow() throws Exception {
        Method m = InstrumentPriceRepository.class.getMethod("deleteFeedPricesOfUntradedInstruments", int.class);
        String sql = m.getAnnotation(Query.class).value();
        assertTrue(sql.startsWith("DELETE FROM instrument_prices"));
        assertTrue(sql.contains("p.user_id IS NULL") && sql.contains("p.source <> 'MANUAL'"));
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM investment_transactions"));
        assertTrue(sql.contains("n.instrument_id = p.instrument_id") && sql.contains("n.as_of > p.as_of"));
    }
}
