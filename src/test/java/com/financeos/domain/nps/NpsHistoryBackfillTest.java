package com.financeos.domain.nps;

import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.price.NpsTrustFeedClient;
import com.financeos.domain.instrument.price.PriceRefreshEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NPS history requests: the shared helper, the statement import and the daily catalog sync. */
class NpsHistoryBackfillTest {

    private final LocalDate from = LocalDate.of(2024, 4, 1);
    private NpsSchemeRepository schemes;
    private InstrumentPriceRepository prices;
    private ApplicationEventPublisher publisher;
    private NpsHistoryBackfill backfill;
    private final UUID coveredId = UUID.randomUUID();
    private final UUID bareId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        schemes = mock(NpsSchemeRepository.class);
        prices = mock(InstrumentPriceRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        backfill = new NpsHistoryBackfill(schemes, prices, publisher);
        when(schemes.findAllById(any())).thenReturn(List.of(scheme("COVERED", coveredId), scheme("BARE", bareId)));
        when(prices.findFirstByInstrumentIdAndUserIdIsNullAndAsOfLessThanEqualOrderByAsOfDesc(eq(coveredId), any()))
                .thenReturn(Optional.of(mock(InstrumentPrice.class)));
        when(prices.findFirstByInstrumentIdAndUserIdIsNullAndAsOfLessThanEqualOrderByAsOfDesc(eq(bareId), any()))
                .thenReturn(Optional.empty());
    }

    private static NpsScheme scheme(String code, UUID instrumentId) {
        NpsScheme s = new NpsScheme();
        s.setSchemeCode(code);
        s.setInstrumentId(instrumentId);
        return s;
    }

    @Test
    void publishesOneEventForTheUncoveredSchemesOnly() {
        int asked = backfill.requestFor(Map.of("COVERED", from, "BARE", from));
        assertEquals(1, asked);
        ArgumentCaptor<PriceRefreshEvent> ev = ArgumentCaptor.forClass(PriceRefreshEvent.class);
        verify(publisher).publishEvent(ev.capture());
        assertEquals(Set.of(bareId), ev.getValue().instrumentIds());
    }

    @Test
    void publishesNothingWhenAllCoveredOrNothingGiven() {
        assertEquals(0, backfill.requestFor(Map.of("COVERED", from)));
        assertEquals(0, backfill.requestFor(Map.of()));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void importRequestsFromTheEarliestEntryOrThePeriodStart() {
        NpsEntryRepository entries = mock(NpsEntryRepository.class);
        NpsHistoryBackfill helper = mock(NpsHistoryBackfill.class);
        NpsStatementImportService service = new NpsStatementImportService(null, entries, null, null, null, null,
                null, null, null, null, helper);
        LocalDate earliest = LocalDate.of(2023, 1, 5);
        when(entries.findEarliestPostedDates(any())).thenReturn(List.<Object[]>of(new Object[]{"BARE", earliest}));

        service.requestHistory(List.of("BARE", "COVERED"), from);

        ArgumentCaptor<Map<String, LocalDate>> map = ArgumentCaptor.forClass(Map.class);
        verify(helper).requestFor(map.capture());
        assertEquals(Map.of("BARE", earliest, "COVERED", from), map.getValue());
    }

    @Test
    void importWithNoMappedSchemesRequestsNothing() {
        NpsHistoryBackfill helper = mock(NpsHistoryBackfill.class);
        NpsStatementImportService service = new NpsStatementImportService(null, mock(NpsEntryRepository.class), null,
                null, null, null, null, null, null, null, helper);
        service.requestHistory(List.of(), from);
        verify(helper, never()).requestFor(any());
    }

    private NpsCatalogService catalog(NpsEntryRepository entries, NpsHistoryBackfill helper) {
        NpsTrustFeedClient feed = mock(NpsTrustFeedClient.class);
        when(feed.all()).thenReturn(List.of(new NpsTrustFeedClient.NavRow("BARE", "n", "p", from, BigDecimal.ONE)));
        NpsCatalogService service = spy(new NpsCatalogService(feed, schemes, null, prices,
                mock(PlatformTransactionManager.class), entries, helper));
        doReturn(1).when(service).apply(any());
        return service;
    }

    @Test
    void syncRequestsHistoryForHeldSchemes() {
        NpsEntryRepository entries = mock(NpsEntryRepository.class);
        NpsHistoryBackfill helper = mock(NpsHistoryBackfill.class);
        when(entries.findEarliestPostedDatesForAllLines()).thenReturn(List.<Object[]>of(new Object[]{"BARE", from}));
        catalog(entries, helper).sync();
        verify(helper).requestFor(Map.of("BARE", from));
    }

    @Test
    void syncWithNothingHeldPublishesNothing() {
        NpsEntryRepository entries = mock(NpsEntryRepository.class);
        when(entries.findEarliestPostedDatesForAllLines()).thenReturn(List.of());
        catalog(entries, new NpsHistoryBackfill(schemes, prices, publisher)).sync();
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void syncPublishesForHeldUncoveredAndNotForCovered() {
        NpsEntryRepository entries = mock(NpsEntryRepository.class);
        when(entries.findEarliestPostedDatesForAllLines())
                .thenReturn(List.<Object[]>of(new Object[]{"COVERED", from}, new Object[]{"BARE", from}));
        catalog(entries, backfill).sync();
        ArgumentCaptor<PriceRefreshEvent> ev = ArgumentCaptor.forClass(PriceRefreshEvent.class);
        verify(publisher).publishEvent(ev.capture());
        assertEquals(Set.of(bareId), ev.getValue().instrumentIds());
    }
}
