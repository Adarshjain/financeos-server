package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The instrument list and the catalog picker search the catalog as the user sees it: the database keeps
 * the type filter and pages, the user's renamed / retyped rows join the first page, a row they retyped
 * away is dropped, and prices come in one batched query.
 */
class InstrumentLocalSearchTest {

    private final UUID user = UUID.randomUUID();
    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private AssetClassOverrideService overrides;
    private Instrument stock;
    private Instrument renamed;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        overrides = mock(AssetClassOverrideService.class);
        stock = instrument("Infosys", "INFY", InstrumentType.stock);
        renamed = instrument("Tata Consultancy", "TCS", InstrumentType.stock);
        UserContext.setCurrentUserId(user);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Instrument instrument(String name, String symbol, InstrumentType type) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setName(name);
        i.setSymbol(symbol);
        i.setType(type);
        return i;
    }

    private InstrumentOverrides mine(String name, InstrumentType type) {
        UserInstrumentOverride row = new UserInstrumentOverride(user, renamed.getId());
        row.setName(name);
        row.setType(type);
        return InstrumentOverrides.of(List.of(row));
    }

    @Test
    void theDatabaseKeepsTheTypeFilterWhenTheUserHasOverrides() {
        InstrumentOverrides view = mine("Zebra Holdings", null);
        when(instrumentRepository.searchInstrumentsPage(eq("inf"), eq(InstrumentType.stock), any())).thenReturn(List.of(stock));

        List<Instrument> found = InstrumentLocalSearch.find(instrumentRepository, "inf", InstrumentType.stock, view, 0, 50);

        assertEquals(List.of(stock), found);
        verify(instrumentRepository).searchInstrumentsPage("inf", InstrumentType.stock, PageRequest.of(0, 50));
    }

    @Test
    void theUsersRenamedRowIsFoundByTheirNameOnTheFirstPageOnly() {
        InstrumentOverrides view = mine("Zebra Holdings", null);
        when(instrumentRepository.findAllById(List.of(renamed.getId()))).thenReturn(List.of(renamed));

        assertEquals(List.of(renamed), InstrumentLocalSearch.find(instrumentRepository, "zebra", null, view, 0, 50));
        assertEquals(List.of(), InstrumentLocalSearch.find(instrumentRepository, "zebra", null, view, 1, 50));
    }

    @Test
    void aRowRetypedIntoTheTypeJoinsAndOneRetypedAwayIsDropped() {
        InstrumentOverrides view = mine(null, InstrumentType.etf);
        when(instrumentRepository.findAllById(List.of(renamed.getId()))).thenReturn(List.of(renamed));
        when(instrumentRepository.searchInstrumentsPage(any(), eq(InstrumentType.stock), any()))
                .thenReturn(List.of(stock, renamed));

        assertEquals(List.of(renamed), InstrumentLocalSearch.find(instrumentRepository, "t", InstrumentType.etf, view, 0, 50));
        assertEquals(List.of(stock), InstrumentLocalSearch.find(instrumentRepository, "t", InstrumentType.stock, view, 0, 50));
    }

    @Test
    void aBlankSearchPagesTheCatalog() {
        when(instrumentRepository.searchInstrumentsPage(isNull(), isNull(), any())).thenReturn(List.of(stock));
        InstrumentLocalSearch.find(instrumentRepository, "  ", null, InstrumentOverrides.NONE, 2, 10);
        verify(instrumentRepository).searchInstrumentsPage(null, null, PageRequest.of(2, 10));
    }

    @Test
    void idsAreChunkedBelowOraclesInListLimit() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 1801; i++) {
            ids.add(i);
        }
        List<List<Integer>> chunks = InstrumentLocalSearch.chunks(ids);
        assertEquals(3, chunks.size());
        assertEquals(900, chunks.get(0).size());
        assertEquals(1, chunks.get(2).size());
    }

    @Test
    void theInstrumentListPagesClampsAndBatchesPrices() {
        // Forced edit: GET /instruments is a page with a total now (listAsSeenBy), not a list.
        InstrumentService service = new InstrumentService(instrumentRepository, priceRepository,
                mock(InstrumentClassificationService.class), overrides, null);
        when(overrides.overridesFor(user)).thenReturn(InstrumentOverrides.NONE);
        PageRequest clamped = PageRequest.of(0, InstrumentService.MAX_PAGE_SIZE);
        when(instrumentRepository.listAsSeenBy(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(stock, renamed), clamped, 2));
        InstrumentPrice price = new InstrumentPrice(stock, LocalDate.of(2026, 10, 9), new BigDecimal("1500"), PriceSource.YAHOO);
        when(priceRepository.findLatestByInstrumentIds(anyCollection(), eq(user))).thenReturn(List.of(price));

        com.financeos.api.instrument.dto.InstrumentListPage page = service.listInstruments(null, null, null, -3, 5000);
        List<InstrumentResponse> out = page.items();

        verify(instrumentRepository).listAsSeenBy(user, null, null, "asc", clamped);
        verify(priceRepository, times(1)).findLatestByInstrumentIds(anyCollection(), eq(user));
        verify(priceRepository, never()).findLatestVisible(any(), any());
        assertEquals(0, new BigDecimal("1500").compareTo(out.get(0).lastPrice()));
        assertEquals(null, out.get(1).lastPrice());
        assertEquals(0, page.page());
        assertEquals(InstrumentService.MAX_PAGE_SIZE, page.size());
        assertEquals(2, page.totalElements());
        assertEquals(1, page.totalPages());
    }

    @Test
    void theCatalogPickerFindsTheUsersRenamedRowAndFiltersOnTheirType() {
        InstrumentSearchService search = new InstrumentSearchService(instrumentRepository, priceRepository,
                mock(InstrumentAliasRepository.class), List.of(), mock(InstrumentClassificationService.class), overrides);
        when(overrides.overridesFor(user)).thenReturn(mine("Zebra Holdings", InstrumentType.etf));
        when(instrumentRepository.findAllById(List.of(renamed.getId()))).thenReturn(List.of(renamed));
        when(instrumentRepository.searchInstrumentsPage(any(), any(), any())).thenReturn(List.of());
        when(priceRepository.findLatestByInstrumentIds(anyCollection(), eq(user))).thenReturn(List.of(
                InstrumentPrice.manual(renamed, user, LocalDate.of(2026, 10, 9), BigDecimal.TEN)));

        List<InstrumentCandidate> etfs = search.catalogSearch("zebra", InstrumentType.etf);
        assertEquals(1, etfs.size());
        assertEquals("Zebra Holdings", etfs.get(0).name());
        assertEquals(renamed.getId(), etfs.get(0).existingInstrumentId());
        assertEquals(0, BigDecimal.TEN.compareTo(etfs.get(0).pricePreview().value()));
        assertTrue(search.catalogSearch("zebra", InstrumentType.stock).isEmpty(), "she sees it as an ETF");
        verify(priceRepository, never()).findLatestVisible(any(), any());
    }
}
