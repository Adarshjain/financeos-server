package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a user sends never edits a shared catalog row ({@link InstrumentSearchService.CatalogWrite#CREATE_ONLY});
 * the import path ({@code TRUSTED}) still refreshes an ISIN match. The local half of the catalog
 * search shows each instrument as the caller sees it.
 */
class InstrumentSearchServiceCatalogWriteTest {

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private InstrumentAliasRepository aliasRepository;
    private AssetClassOverrideService overrides;
    private InstrumentSearchService service;
    private Instrument existing;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        aliasRepository = mock(InstrumentAliasRepository.class);
        overrides = mock(AssetClassOverrideService.class);
        service = new InstrumentSearchService(instrumentRepository, priceRepository, aliasRepository, List.of(),
                mock(InstrumentClassificationService.class), overrides);
        existing = new Instrument();
        existing.setId(UUID.randomUUID());
        existing.setType(InstrumentType.stock);
        existing.setName("Old Name Ltd");
        existing.setSymbol("OLD");
        existing.setExchange("NSE");
        existing.setIsin("INE000A01011");
        existing.setYahooSymbol("OLD.NS");
        when(instrumentRepository.findByIsin("INE000A01011")).thenReturn(Optional.of(existing));
        when(instrumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static ResolveInstrumentRequest renamed() {
        return new ResolveInstrumentRequest(InstrumentType.stock, "New Name Ltd", "NEW", "BSE", "INE000A01011", "999",
                "NEW.NS", "INR", null);
    }

    @Test
    void createOnlyReusesAnIsinMatchAsItIs() {
        Instrument resolved = service.resolveInstrument(renamed(), InstrumentSearchService.CatalogWrite.CREATE_ONLY);

        assertSame(existing, resolved);
        assertEquals("Old Name Ltd", existing.getName());
        assertEquals("OLD", existing.getSymbol());
        assertEquals("NSE", existing.getExchange());
        assertEquals("OLD.NS", existing.getYahooSymbol());
        assertEquals(null, existing.getAmfiCode(), "not even an empty identifier is filled");
        verify(aliasRepository, never()).save(any());
        verify(instrumentRepository, never()).save(any());
    }

    @Test
    void createOnlyNeverFillsAnEmptyIdentifierOfAnotherMatch() {
        Instrument byYahoo = new Instrument();
        byYahoo.setId(UUID.randomUUID());
        byYahoo.setType(InstrumentType.stock);
        byYahoo.setName("Y");
        byYahoo.setAssetClass(AssetClass.EQUITY);
        when(instrumentRepository.findByYahooSymbol("Y.NS")).thenReturn(Optional.of(byYahoo));

        Instrument resolved = service.resolveInstrument(new ResolveInstrumentRequest(InstrumentType.stock, "Y", "Y", "NSE",
                "INE999Z01011", null, "Y.NS", null, null), InstrumentSearchService.CatalogWrite.CREATE_ONLY);

        assertSame(byYahoo, resolved);
        assertEquals(null, byYahoo.getIsin());
        verify(instrumentRepository, never()).save(any());
    }

    @Test
    void createOnlyAddsANewRowWhenNothingMatches() {
        Instrument created = service.resolveInstrument(new ResolveInstrumentRequest(InstrumentType.etf, " Fresh ETF ", null,
                null, "INF111A01011", null, null, null, null), InstrumentSearchService.CatalogWrite.CREATE_ONLY);
        assertEquals("Fresh ETF", created.getName());
        assertEquals("INF111A01011", created.getIsin());
        assertEquals("INR", created.getCurrency());
        verify(instrumentRepository).save(created);
    }

    @Test
    void theImportPathStillRefreshesAnIsinMatch() {
        service.resolveInstrument(renamed(), InstrumentSearchService.CatalogWrite.TRUSTED);
        assertEquals("New Name Ltd", existing.getName());
        assertEquals("NEW", existing.getSymbol());
        verify(aliasRepository).save(any(InstrumentAlias.class));
    }

    @Test
    void feedIdentifiersResolveInOrderOfAuthority() {
        Instrument byAmfi = new Instrument();
        byAmfi.setId(UUID.randomUUID());
        when(instrumentRepository.findByAmfiCode("120503")).thenReturn(Optional.of(byAmfi));

        assertSame(existing, service.findByFeedIdentifiers("INE000A01011", "120503", null).orElseThrow());
        assertSame(byAmfi, service.findByFeedIdentifiers(" ", " 120503 ", "X.NS").orElseThrow());
        assertTrue(service.findByFeedIdentifiers(null, null, null).isEmpty());
        verify(instrumentRepository, never()).findByYahooSymbol("X.NS");
    }

    @Test
    void localCandidatesShowTheCallersNamesAndPrices() {
        UserContext.setCurrentUserId(user);
        UserInstrumentOverride row = new UserInstrumentOverride(user, existing.getId());
        row.setName("My Old");
        row.setType(InstrumentType.etf);
        when(overrides.overridesFor(user)).thenReturn(InstrumentOverrides.of(List.of(row)));
        // Forced edit: the local half now pages in the database and batches the latest prices.
        when(instrumentRepository.searchInstrumentsPage(org.mockito.ArgumentMatchers.eq("old"),
                org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(List.of(existing));
        LocalDate d = LocalDate.of(2026, 10, 9);
        when(priceRepository.findLatestByInstrumentIds(List.of(existing.getId()), user)).thenReturn(List.of(
                new InstrumentPrice(existing, d, BigDecimal.ONE, PriceSource.YAHOO),
                InstrumentPrice.manual(existing, user, d, BigDecimal.TEN)));

        InstrumentCandidate local = service.catalogSearch("old", null).get(0);

        assertEquals("LOCAL", local.source());
        assertEquals("My Old", local.name());
        assertEquals(InstrumentType.etf, local.type());
        assertEquals("OLD", local.symbol());
        assertEquals(0, BigDecimal.TEN.compareTo(local.pricePreview().value()));
    }
}
