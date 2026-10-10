package com.financeos.domain.instrument;

import com.financeos.api.instrument.dto.InstrumentPriceResponse;
import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.UpsertPriceRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InstrumentService}'s per-user rules: edits are the caller's overrides, identifier edits
 * repoint, and manual prices are the caller's own.
 */
class InstrumentServiceUserScopeTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private AssetClassOverrideService overrides;
    private InstrumentRepointService repoint;
    private InstrumentService service;
    private final UUID user = UUID.randomUUID();
    private Instrument instrument;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        overrides = mock(AssetClassOverrideService.class);
        repoint = mock(InstrumentRepointService.class);
        service = new InstrumentService(instrumentRepository, priceRepository, mock(InstrumentClassificationService.class),
                overrides, repoint);
        instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.stock);
        instrument.setName("Infosys");
        instrument.setSymbol("INFY");
        instrument.setIsin("INE009A01021");
        instrument.setYahooSymbol("INFY.NS");
        when(instrumentRepository.findById(instrument.getId())).thenReturn(Optional.of(instrument));
        when(instrumentRepository.existsById(instrument.getId())).thenReturn(true);
        UserContext.setCurrentUserId(user);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    private InstrumentRequest request(String isin, String amfi, String yahoo) {
        return new InstrumentRequest(InstrumentType.stock, "Infosys", "INFY", null, isin, amfi, yahoo, null);
    }

    // ------------------------------------------------------------------ identifiers

    @Test
    void identifiersCompareTrimmedCaseInsensitiveWithBlankAsNone() {
        assertFalse(InstrumentService.identifiersChanged(instrument, request(" ine009a01021 ", "", "infy.ns")));
        assertTrue(InstrumentService.identifiersChanged(instrument, request("INE009A01021", "120503", "INFY.NS")));
        assertTrue(InstrumentService.identifiersChanged(instrument, request("INE009A01021", null, null)));
        assertTrue(InstrumentService.identifiersChanged(instrument, request("INE000000000", null, "INFY.NS")));
    }

    @Test
    void aDisplayOnlyEditNeverRepoints() {
        service.updateInstrument(instrument.getId(), new InstrumentRequest(InstrumentType.etf, "My Infy", "MI", "BSE",
                "INE009A01021", null, "INFY.NS", "USD"));
        verify(overrides).setDisplay(user, instrument, "My Infy", "MI", "BSE", "USD", InstrumentType.etf);
        verifyNoInteractions(repoint);
    }

    @Test
    void anIdentifierEditRepointsAndAppliesOnlyTheChangedDisplayFieldsToTheTarget() {
        // Was anIdentifierEditRepointsAndAppliesTheDisplayFieldsToTheTarget: display values the user left
        // as they saw them on the source are no longer pinned over the target's own.
        Instrument target = new Instrument();
        target.setId(UUID.randomUUID());
        target.setType(InstrumentType.stock);
        target.setName("Infosys (new)");
        InstrumentRequest req = request("INE009A01099", null, "INFY.NS");
        when(repoint.repoint(user, instrument, req, DisplayChanges.NONE))
                .thenReturn(new InstrumentRepointService.Result(target, false, false, false));

        InstrumentResponse response = service.updateInstrument(instrument.getId(), req);

        verify(overrides).setDisplay(user, target, "Infosys", "INFY", null, null, InstrumentType.stock, DisplayChanges.NONE);
        assertEquals(target.getId(), response.id());
        assertFalse(response.mergedHoldings());
        assertNull(response.mergeNote());
    }

    @Test
    void clearingEveryIdentifierIsRejected() {
        assertThrows(ValidationException.class,
                () -> service.updateInstrument(instrument.getId(), request(null, " ", null)));
        verifyNoInteractions(repoint);
        verify(overrides, never()).setDisplay(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anEditNeedsASignedInUser() {
        UserContext.clear();
        assertThrows(ValidationException.class,
                () -> service.updateInstrument(instrument.getId(), request("INE009A01021", null, "INFY.NS")));
        verifyNoInteractions(overrides);
    }

    @Test
    void resetClearsTheCallersOverridesAndShowsTheCatalog() {
        InstrumentResponse response = service.resetOverrides(instrument.getId());
        verify(overrides).clear(user, instrument.getId());
        assertEquals("Infosys", response.name());
        assertFalse(response.overridden());
        assertEquals(List.of(), response.overriddenFields());
    }

    @Test
    void resetOfAnUnknownInstrumentIs404() {
        UUID id = UUID.randomUUID();
        when(instrumentRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.resetOverrides(id));
        verifyNoInteractions(overrides);
    }

    @Test
    void readsApplyTheCallersOverrides() {
        UserInstrumentOverride row = new UserInstrumentOverride(user, instrument.getId());
        row.setName("My Infy");
        when(overrides.overridesFor(user)).thenReturn(InstrumentOverrides.of(List.of(row)));

        InstrumentResponse response = service.getInstrumentById(instrument.getId());

        assertEquals("My Infy", response.name());
        assertTrue(response.overridden());
        assertEquals(List.of("name"), response.overriddenFields());
        assertEquals("INFY.NS", response.yahooSymbol(), "identifiers stay the catalog's");
    }

    @Test
    void searchFindsTheCallersOwnNamesAndFiltersOnTheirType() {
        // Forced edit: the user's own names and types are filtered, sorted and counted in the query
        // (listAsSeenBy joins their override row; InstrumentListIntegrationTest runs it), so here the
        // service hands it the caller, the lowercased text, the type and the direction, and shows the
        // rows with the caller's overrides.
        UserInstrumentOverride row = new UserInstrumentOverride(user, instrument.getId());
        row.setName("Bluechip IT");
        row.setType(InstrumentType.etf);
        when(overrides.overridesFor(user)).thenReturn(InstrumentOverrides.of(List.of(row)));
        org.springframework.data.domain.PageRequest first = org.springframework.data.domain.PageRequest.of(0, 50);
        when(instrumentRepository.listAsSeenBy(user, "bluechip", InstrumentType.etf, "desc", first))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(instrument), first, 1));

        com.financeos.api.instrument.dto.InstrumentListPage asEtf =
                service.listInstruments("  BlueChip ", InstrumentType.etf, "name,DESC", 0, 50);
        assertEquals(1, asEtf.items().size());
        assertEquals(1, asEtf.totalElements());
        assertEquals("Bluechip IT", asEtf.items().get(0).name());
        assertEquals(InstrumentType.etf, asEtf.items().get(0).type());
    }

    @Test
    void searchWithoutOverridesFiltersTypeInTheQuery() {
        // Forced edit: the query is the paged, counted listAsSeenBy (first page, 50, by name ascending).
        org.springframework.data.domain.PageRequest first = org.springframework.data.domain.PageRequest.of(0, 50);
        when(overrides.overridesFor(user)).thenReturn(InstrumentOverrides.NONE);
        when(instrumentRepository.listAsSeenBy(user, "inf", InstrumentType.stock, "asc", first))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(instrument), first, 1));
        assertEquals(1, service.listInstruments("inf", InstrumentType.stock, null, 0, 50).items().size());
    }

    @Test
    void anUnknownSortKeyOrDirectionIsRejected() {
        assertEquals("asc", InstrumentService.sortDirection("name"));
        assertEquals("desc", InstrumentService.sortDirection(" name , desc "));
        assertEquals("asc", InstrumentService.sortDirection(""));
        assertThrows(ValidationException.class, () -> service.listInstruments(null, null, "lastPrice,desc", 0, 50));
        assertThrows(ValidationException.class, () -> service.listInstruments(null, null, "name,up", 0, 50));
        assertThrows(ValidationException.class, () -> service.listInstruments(null, null, "name,asc,x", 0, 50));
        verify(instrumentRepository, never()).listAsSeenBy(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ manual prices

    @Test
    void upsertCreatesTheCallersOwnManualRowForToday() {
        when(priceRepository.findByInstrumentIdAndAsOfAndUserId(instrument.getId(), TODAY, user)).thenReturn(Optional.empty());

        service.upsertPrice(instrument.getId(), new UpsertPriceRequest(new BigDecimal("1500"), null));

        ArgumentCaptor<InstrumentPrice> saved = ArgumentCaptor.forClass(InstrumentPrice.class);
        verify(priceRepository).save(saved.capture());
        assertEquals(user, saved.getValue().getUserId());
        assertEquals(PriceSource.MANUAL, saved.getValue().getSource());
        assertEquals(TODAY, saved.getValue().getAsOf());
        assertEquals(0, new BigDecimal("1500").compareTo(saved.getValue().getClose()));
    }

    @Test
    void upsertUpdatesTheCallersRowOfThatDateAndNeverAFeedRow() {
        LocalDate d = TODAY.minusDays(2);
        InstrumentPrice mine = InstrumentPrice.manual(instrument, user, d, BigDecimal.ONE);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserId(instrument.getId(), d, user)).thenReturn(Optional.of(mine));

        service.upsertPrice(instrument.getId(), new UpsertPriceRequest(new BigDecimal("9"), d));

        assertEquals(0, new BigDecimal("9").compareTo(mine.getClose()));
        verify(priceRepository).save(mine);
        verify(priceRepository, never()).findByInstrumentIdAndAsOfAndUserIdIsNull(any(), any());
    }

    @Test
    void upsertRejectsANegativePriceAndAnAnonymousCaller() {
        assertThrows(ValidationException.class,
                () -> service.upsertPrice(instrument.getId(), new UpsertPriceRequest(new BigDecimal("-1"), null)));
        UserContext.clear();
        assertThrows(ValidationException.class,
                () -> service.upsertPrice(instrument.getId(), new UpsertPriceRequest(BigDecimal.TEN, null)));
        verify(priceRepository, never()).save(any());
    }

    private InstrumentPrice stored(InstrumentPrice p) {
        p.setId(UUID.randomUUID());
        when(priceRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void onlyTheCallersOwnManualPriceCanBeEditedOrDeleted() {
        InstrumentPrice mine = stored(InstrumentPrice.manual(instrument, user, TODAY, BigDecimal.ONE));
        InstrumentPrice theirs = stored(InstrumentPrice.manual(instrument, UUID.randomUUID(), TODAY, BigDecimal.ONE));
        InstrumentPrice feed = stored(new InstrumentPrice(instrument, TODAY, BigDecimal.ONE, PriceSource.YAHOO));
        InstrumentPrice legacy = stored(new InstrumentPrice(instrument, TODAY, BigDecimal.ONE, PriceSource.MANUAL));

        for (InstrumentPrice other : List.of(theirs, feed, legacy)) {
            assertThrows(ResourceNotFoundException.class,
                    () -> service.updateManualPrice(instrument.getId(), other.getId(), BigDecimal.TEN));
            assertThrows(ResourceNotFoundException.class,
                    () -> service.deleteManualPrice(instrument.getId(), other.getId()));
        }
        verify(priceRepository, never()).save(any());
        verify(priceRepository, never()).delete(any());

        service.updateManualPrice(instrument.getId(), mine.getId(), BigDecimal.TEN);
        assertEquals(0, BigDecimal.TEN.compareTo(mine.getClose()));
        service.deleteManualPrice(instrument.getId(), mine.getId());
        verify(priceRepository).delete(mine);
    }

    @Test
    void aPriceOfAnotherInstrumentOrAnUnknownIdIs404() {
        InstrumentPrice mine = stored(InstrumentPrice.manual(instrument, user, TODAY, BigDecimal.ONE));
        UUID otherInstrument = UUID.randomUUID();
        assertThrows(ResourceNotFoundException.class,
                () -> service.updateManualPrice(otherInstrument, mine.getId(), BigDecimal.TEN));
        assertThrows(ResourceNotFoundException.class,
                () -> service.deleteManualPrice(instrument.getId(), UUID.randomUUID()));
        assertThrows(ValidationException.class,
                () -> service.updateManualPrice(instrument.getId(), mine.getId(), new BigDecimal("-2")));
    }

    @Test
    void historyIsTheCallersViewWithTheirRowsEditable() {
        LocalDate d = TODAY.minusDays(1);
        InstrumentPrice feedSameDay = new InstrumentPrice(instrument, d, BigDecimal.ONE, PriceSource.YAHOO);
        InstrumentPrice mine = InstrumentPrice.manual(instrument, user, d, BigDecimal.TEN);
        InstrumentPrice older = new InstrumentPrice(instrument, d.minusDays(1), BigDecimal.TWO, PriceSource.YAHOO);
        when(priceRepository.findPriceHistory(instrument.getId(), user, null, null))
                .thenReturn(List.of(feedSameDay, mine, older));

        List<InstrumentPriceResponse> history = service.getPriceHistory(instrument.getId(), null, null);

        assertEquals(2, history.size());
        assertEquals(PriceSource.MANUAL, history.get(0).source());
        assertTrue(history.get(0).editable());
        assertFalse(history.get(1).editable());
    }

    @Test
    void theResponsesLatestPriceIsTheCallersView() {
        InstrumentPrice feed = new InstrumentPrice(instrument, TODAY, BigDecimal.ONE, PriceSource.YAHOO);
        InstrumentPrice mine = InstrumentPrice.manual(instrument, user, TODAY, BigDecimal.TEN);
        when(priceRepository.findLatestVisible(eq(instrument.getId()), eq(user))).thenReturn(List.of(feed, mine));

        InstrumentResponse response = service.getInstrumentById(instrument.getId());

        assertEquals(0, BigDecimal.TEN.compareTo(response.lastPrice()));
        assertEquals(PriceSource.MANUAL, response.lastPriceSource());
    }

    @Test
    void anAssetClassPatchKeepsTheResponseConsistent() {
        InstrumentResponse response = service.updateAssetClass(instrument.getId(), AssetClass.GOLD);
        assertEquals(AssetClass.GOLD, response.assetClass());
        assertTrue(response.overridden());
        assertEquals(List.of("assetClass"), response.overriddenFields());
        assertSame(null, response.schemeCategory());
        assertNull(response.exchange());
    }
}
