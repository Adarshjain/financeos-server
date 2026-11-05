package com.financeos.domain.instrument.price;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.investment.InvestmentTransactionRepository;
import com.financeos.domain.nps.NpsEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The history backfill covers nps_scheme instruments: NPS Trust history, window from the earliest NPS entry. */
class NpsPriceHistoryBackfillTest {

    private final LocalDate today = AppTime.today();
    private InstrumentRepository instruments;
    private InvestmentTransactionRepository trades;
    private NpsEntryRepository npsEntries;
    private PriceHistoryClient client;
    private JdbcTemplate jdbc;
    private PriceHistoryService service;
    private UUID id;

    @BeforeEach
    void setUp() {
        instruments = mock(InstrumentRepository.class);
        trades = mock(InvestmentTransactionRepository.class);
        npsEntries = mock(NpsEntryRepository.class);
        client = mock(PriceHistoryClient.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PriceHistoryService(new PriceProperties(), instruments, mock(InstrumentPriceRepository.class),
                trades, client, jdbc, npsEntries);
        id = UUID.randomUUID();
        when(jdbc.batchUpdate(anyString(), anyCollection(), anyInt(), any())).thenAnswer(inv -> {
            Collection<?> rows = inv.getArgument(1);
            int[] ones = new int[rows.size()];
            Arrays.fill(ones, 1);
            return new int[][]{ones};
        });
    }

    private Instrument scheme() {
        Instrument i = new Instrument();
        i.setId(id);
        i.setType(InstrumentType.nps_scheme);
        i.setSymbol("SM008001");
        return i;
    }

    @Test
    void windowStartsOneYearBeforeTheEarliestEntryAndWritesNpsTrustRows() {
        LocalDate firstEntry = today.minusYears(5);
        when(instruments.findAllById(any())).thenReturn(List.of(scheme()));
        when(trades.findEarliestTradeDates(any())).thenReturn(new ArrayList<>());
        when(npsEntries.findEarliestPostedDates(any())).thenReturn(List.<Object[]>of(new Object[]{"SM008001", firstEntry}));
        LocalDate d1 = today.minusDays(3);
        when(client.fetchNpsScheme(eq("SM008001"), any(), any())).thenReturn(new PriceHistoryClient.Result(
                PriceHistoryClient.Status.OK, List.of(new PriceHistoryClient.DailyClose(d1, new BigDecimal("53.1")))));

        int written = service.backfill(List.of(id));

        assertEquals(1, written);
        verify(client).fetchNpsScheme("SM008001", firstEntry.minusYears(1), today.minusDays(1));
        ArgumentCaptor<ParameterizedPreparedStatementSetter<PriceHistoryClient.DailyClose>> setter = ArgumentCaptor.forClass(ParameterizedPreparedStatementSetter.class);
        verify(jdbc).batchUpdate(anyString(), anyCollection(), anyInt(), setter.capture());
        PreparedStatement ps = mock(PreparedStatement.class);
        try {
            setter.getValue().setValues(ps, new PriceHistoryClient.DailyClose(d1, new BigDecimal("53.1")));
            verify(ps).setString(4, PriceSource.NPS_TRUST.name());
        } catch (java.sql.SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void withNoEntryTheWindowIsTheDefaultTwoYears() {
        when(instruments.findAllById(any())).thenReturn(List.of(scheme()));
        when(trades.findEarliestTradeDates(any())).thenReturn(new ArrayList<>());
        when(npsEntries.findEarliestPostedDates(any())).thenReturn(new ArrayList<>());
        when(client.fetchNpsScheme(any(), any(), any())).thenReturn(new PriceHistoryClient.Result(
                PriceHistoryClient.Status.OK, List.of()));

        service.backfill(List.of(id));

        verify(client).fetchNpsScheme("SM008001", today.minusYears(2), today.minusDays(1));
    }

    @Test
    void aFailedDownloadWritesNothing() {
        when(instruments.findAllById(any())).thenReturn(List.of(scheme()));
        when(trades.findEarliestTradeDates(any())).thenReturn(new ArrayList<>());
        when(npsEntries.findEarliestPostedDates(any())).thenReturn(new ArrayList<>());
        when(client.fetchNpsScheme(any(), any(), any())).thenReturn(new PriceHistoryClient.Result(
                PriceHistoryClient.Status.FAILED, List.of()));

        assertEquals(0, service.backfill(List.of(id)));
        verify(jdbc, never()).batchUpdate(anyString(), anyCollection(), anyInt(), any());
    }
}
