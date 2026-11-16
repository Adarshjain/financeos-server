package com.financeos.domain.instrument.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** A merger/demerger target has no trades; the bulk refresh and the history backfill must still reach it. */
class MergerTargetPriceSelectionTest {

    private InstrumentRepository instruments;
    private HoldingRepository holdings;
    private CorporateActionRepository actions;
    private PriceProvider yahoo;
    private PriceRefreshService refresh;
    private Instrument traded;
    private Instrument target;

    @BeforeEach
    void setUp() {
        instruments = mock(InstrumentRepository.class);
        holdings = mock(HoldingRepository.class);
        actions = mock(CorporateActionRepository.class);
        yahoo = mock(PriceProvider.class);
        when(yahoo.source()).thenReturn(PriceSource.YAHOO);
        when(yahoo.supports(any())).thenReturn(true);
        InstrumentPriceRepository prices = mock(InstrumentPriceRepository.class);
        when(prices.findByInstrumentIdAndAsOfAndUserIdIsNull(any(), any())).thenReturn(Optional.empty());
        refresh = new PriceRefreshService(instruments, prices, holdings, List.of(yahoo), new PriceProperties(),
                mock(InstrumentClassificationService.class));
        refresh.setCorporateActionRepository(actions);
        traded = stock("ACME");
        target = stock("TMCV");
        when(holdings.findDistinctActiveInstrumentIdsHeld()).thenReturn(List.of(traded.getId()));
        when(actions.findDistinctMergerTargetInstrumentIds()).thenReturn(List.of(target.getId(), traded.getId()));
        when(instruments.findAllById(any())).thenAnswer(inv -> {
            List<Instrument> out = new ArrayList<>();
            for (UUID id : (Iterable<UUID>) inv.getArgument(0)) {
                out.add(id.equals(traded.getId()) ? traded : target);
            }
            return out;
        });
        when(yahoo.fetch(any())).thenReturn(Map.of());
        when(yahoo.fetchLive(any())).thenReturn(Map.of());
    }

    private static Instrument stock(String symbol) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName(symbol);
        i.setSymbol(symbol);
        i.setYahooSymbol(symbol + ".NS");
        return i;
    }

    @Test
    void theDailyRefreshFetchesATargetWithNoTradesOnce() {
        refresh.refresh(Optional.empty());

        ArgumentCaptor<List<Instrument>> fetched = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(yahoo).fetch(fetched.capture());
        assertThat(fetched.getValue()).containsExactlyInAnyOrder(traded, target);
    }

    @Test
    void theIntradayRefreshFetchesATargetWithNoTrades() {
        refresh.refreshIntraday();

        ArgumentCaptor<List<Instrument>> fetched = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(yahoo).fetchLive(fetched.capture());
        assertThat(fetched.getValue()).containsExactlyInAnyOrder(traded, target);
    }

    @Test
    void aNotTradingTargetIsStillSkippedByTheRecheckGate() {
        target.setPriceStatus("not_trading");
        target.setPriceStatusAt(java.time.Instant.now());

        refresh.refresh(Optional.empty());

        ArgumentCaptor<List<Instrument>> fetched = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(yahoo).fetch(fetched.capture());
        assertThat(fetched.getValue()).containsExactly(traded);
    }

    @Test
    void historyStartsAtTheExDateWhenTheTargetHasNoTrades() {
        PriceHistoryClient client = mock(PriceHistoryClient.class);
        when(client.fetchYahoo(any(), any(), any())).thenReturn(
                new PriceHistoryClient.Result(PriceHistoryClient.Status.FAILED, List.of()));
        InvestmentTransactionRepository trades = mock(InvestmentTransactionRepository.class);
        when(trades.findEarliestTradeDates(any())).thenReturn(new ArrayList<>());
        PriceProperties props = new PriceProperties();
        PriceHistoryService history = new PriceHistoryService(props, instruments, mock(InstrumentPriceRepository.class),
                trades, client, mock(org.springframework.jdbc.core.JdbcTemplate.class));
        history.setCorporateActionRepository(actions);
        LocalDate today = AppTime.today();
        LocalDate exDate = today.minusYears(8);
        when(actions.findEarliestExDatesByTarget(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{target.getId().toString(), exDate}));
        org.mockito.Mockito.doReturn(List.of(target)).when(instruments).findAllById(any());

        history.backfill(List.of(target.getId()));

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(client).fetchYahoo(any(), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(PriceHistoryService.windowStart(today, exDate,
                props.getHistory().getMinYears(), props.getHistory().getYearsBeforeFirstTrade()));
        assertThat(from.getValue()).isBefore(today.minusYears(8));
    }

    @Test
    void anEarlierTradeBeatsALaterExDate() {
        PriceHistoryClient client = mock(PriceHistoryClient.class);
        when(client.fetchYahoo(any(), any(), any())).thenReturn(
                new PriceHistoryClient.Result(PriceHistoryClient.Status.FAILED, List.of()));
        InvestmentTransactionRepository trades = mock(InvestmentTransactionRepository.class);
        LocalDate today = AppTime.today();
        LocalDate trade = today.minusYears(9);
        when(trades.findEarliestTradeDates(any())).thenReturn(List.<Object[]>of(new Object[]{target.getId().toString(), trade}));
        PriceProperties props = new PriceProperties();
        PriceHistoryService history = new PriceHistoryService(props, instruments, mock(InstrumentPriceRepository.class),
                trades, client, mock(org.springframework.jdbc.core.JdbcTemplate.class));
        history.setCorporateActionRepository(actions);
        when(actions.findEarliestExDatesByTarget(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{target.getId().toString(), today.minusYears(3)}));
        org.mockito.Mockito.doReturn(List.of(target)).when(instruments).findAllById(any());

        history.backfill(List.of(target.getId()));

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.Mockito.verify(client).fetchYahoo(any(), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(PriceHistoryService.windowStart(today, trade,
                props.getHistory().getMinYears(), props.getHistory().getYearsBeforeFirstTrade()));
    }
}
