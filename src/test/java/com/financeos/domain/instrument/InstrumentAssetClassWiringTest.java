package com.financeos.domain.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.InstrumentRequest;
import com.financeos.api.instrument.dto.InstrumentResponse;
import com.financeos.api.instrument.dto.ResolveInstrumentRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.price.PriceProperties;
import com.financeos.domain.instrument.price.PriceProvider;
import com.financeos.domain.instrument.price.PriceRefreshResult;
import com.financeos.domain.instrument.price.PriceRefreshService;
import com.financeos.domain.instrument.search.InstrumentSearchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Where asset classes get filled: create / edit / catalog pick / price refresh, plus the manual
 * override (PATCH) and the classification on InstrumentResponse.
 */
class InstrumentAssetClassWiringTest {

    private InstrumentRepository instrumentRepository;
    private InstrumentPriceRepository priceRepository;
    private InstrumentClassificationService classification;
    private AssetClassOverrideService overrides;
    private InstrumentService instrumentService;

    @BeforeEach
    void setUp() {
        instrumentRepository = mock(InstrumentRepository.class);
        priceRepository = mock(InstrumentPriceRepository.class);
        classification = mock(InstrumentClassificationService.class);
        overrides = mock(AssetClassOverrideService.class);
        instrumentService = new InstrumentService(instrumentRepository, priceRepository, mock(InstrumentAliasRepository.class),
                mock(ApplicationEventPublisher.class), classification, overrides);
        when(instrumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(priceRepository.findLatestVisible(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
    }

    private static Instrument instrument(InstrumentType type, String name) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(type);
        i.setName(name);
        return i;
    }

    private static InstrumentRequest request(InstrumentType type, String name) {
        return new InstrumentRequest(type, name, null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------ InstrumentService

    @Test
    void createClassifiesBeforeSaving() {
        instrumentService.createInstrument(request(InstrumentType.stock, "Reliance"));
        verify(classification).classify(any(Instrument.class));
        verify(instrumentRepository).save(any(Instrument.class));
    }

    @Test
    void updateIsTheCallersOverrideAndLeavesTheSharedRowUnclassifiedAgain() {
        // Was updateClassifiesAgain: an edit no longer rewrites (or reclassifies) the shared row.
        Instrument inst = instrument(InstrumentType.mutual_fund, "Old");
        when(instrumentRepository.findById(inst.getId())).thenReturn(Optional.of(inst));
        UUID user = UUID.randomUUID();
        UserContext.setCurrentUserId(user);
        try {
            instrumentService.updateInstrument(inst.getId(), request(InstrumentType.mutual_fund, "New"));

            verify(overrides).setDisplay(user, inst, "New", null, null, null, InstrumentType.mutual_fund);
            verify(classification, never()).classify(any());
            verify(instrumentRepository, never()).save(any());
            assertEquals("Old", inst.getName(), "the shared row keeps its name");
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void patchPinsTheCurrentUsersOverrideAndLeavesTheSharedInstrumentAlone() {
        Instrument inst = instrument(InstrumentType.mutual_fund, "Axis Midcap Fund");
        inst.setAssetClass(AssetClass.EQUITY);
        inst.setAssetClassSource(AssetClassSource.AMFI);
        when(instrumentRepository.findById(inst.getId())).thenReturn(Optional.of(inst));
        UUID user = UUID.randomUUID();
        UserContext.setCurrentUserId(user);
        try {
            InstrumentResponse response = instrumentService.updateAssetClass(inst.getId(), AssetClass.INTERNATIONAL);

            verify(overrides).set(user, inst.getId(), AssetClass.INTERNATIONAL);
            assertEquals(AssetClass.EQUITY, inst.getAssetClass(), "the shared row keeps its global class");
            assertEquals(AssetClassSource.AMFI, inst.getAssetClassSource());
            verify(instrumentRepository, never()).save(any());
            assertEquals(AssetClass.INTERNATIONAL, response.assetClass());
            assertEquals(AssetClassSource.MANUAL, response.assetClassSource());
            assertEquals(TaxClass.OTHER, response.taxClass());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void patchWithNullRemovesTheOverrideAndShowsTheGlobalClass() {
        Instrument inst = instrument(InstrumentType.mutual_fund, "Axis Midcap Fund");
        inst.setAssetClass(AssetClass.EQUITY);
        inst.setAssetClassSource(AssetClassSource.RULE);
        when(instrumentRepository.findById(inst.getId())).thenReturn(Optional.of(inst));
        UUID user = UUID.randomUUID();
        UserContext.setCurrentUserId(user);
        try {
            InstrumentResponse response = instrumentService.updateAssetClass(inst.getId(), null);

            verify(overrides).set(user, inst.getId(), null);
            assertEquals(AssetClass.EQUITY, response.assetClass());
            assertEquals(AssetClassSource.RULE, response.assetClassSource());
            assertEquals(TaxClass.EQUITY_ORIENTED, response.taxClass());
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void patchWithoutASignedInUserIsRejected() {
        Instrument inst = instrument(InstrumentType.mutual_fund, "Axis Midcap Fund");
        when(instrumentRepository.findById(inst.getId())).thenReturn(Optional.of(inst));
        UserContext.clear();
        assertThrows(com.financeos.core.exception.ValidationException.class,
                () -> instrumentService.updateAssetClass(inst.getId(), AssetClass.DEBT));
        verifyNoInteractions(overrides);
    }

    @Test
    void anOverrideOnTheResponseReplacesTheClassAndItsTaxClass() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "HDFC Liquid Fund");
        InstrumentResponse global = InstrumentResponse.from(fund, Optional.empty());
        assertEquals(AssetClass.DEBT, global.assetClass());
        assertEquals(TaxClass.SPECIFIED_DEBT, global.taxClass());

        // withAssetClassOverride is gone (one classification path): the override goes in as overrides.
        InstrumentResponse mine = InstrumentResponse.from(fund, Optional.empty(), AssetClass.EQUITY);
        assertEquals(AssetClass.EQUITY, mine.assetClass());
        assertEquals(AssetClassSource.MANUAL, mine.assetClassSource());
        assertEquals(TaxClass.EQUITY_ORIENTED, mine.taxClass());
        assertEquals(global.name(), mine.name());
        assertEquals(global, InstrumentResponse.from(fund, Optional.empty(), (AssetClass) null));
    }

    @Test
    void patchOfAnUnknownInstrumentIs404() {
        UUID id = UUID.randomUUID();
        when(instrumentRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> instrumentService.updateAssetClass(id, AssetClass.DEBT));
    }

    @Test
    void theResponseDerivesTheClassWhileNothingIsStored() {
        Instrument fund = instrument(InstrumentType.mutual_fund, "HDFC Liquid Fund");
        fund.setSchemeCategory(null);

        InstrumentResponse r = InstrumentResponse.from(fund, Optional.empty());

        assertEquals(AssetClass.DEBT, r.assetClass());
        assertNull(r.assetClassSource());
        assertNull(r.schemeCategory());
        assertEquals(TaxClass.SPECIFIED_DEBT, r.taxClass());
    }

    // ------------------------------------------------------------------ catalog pick

    private InstrumentSearchService searchService() {
        return new InstrumentSearchService(instrumentRepository, priceRepository, mock(InstrumentAliasRepository.class),
                List.of(), classification);
    }

    @Test
    void pickingANewInstrumentClassifiesIt() {
        when(instrumentRepository.findByIsin(any())).thenReturn(Optional.empty());
        when(instrumentRepository.findByAmfiCode(any())).thenReturn(Optional.empty());

        searchService().resolve(new ResolveInstrumentRequest(InstrumentType.mutual_fund, "ABSL Frontline", null, null,
                "INF209K01YN0", "119551", null, "INR", null));

        verify(classification).classify(any(Instrument.class));
    }

    @Test
    void pickingAnExistingUnclassifiedInstrumentByIdClassifiesAndSavesIt() {
        Instrument inst = instrument(InstrumentType.etf, "Gold BeES");
        when(instrumentRepository.findById(inst.getId())).thenReturn(Optional.of(inst));
        when(classification.classify(inst)).thenReturn(true);

        searchService().resolve(new ResolveInstrumentRequest(InstrumentType.etf, "Gold BeES", null, null, null, null, null,
                "INR", inst.getId()));

        verify(classification).classify(inst);
        verify(instrumentRepository).save(inst);
    }

    @Test
    void pickingAnAlreadyClassifiedInstrumentByIdLeavesIt() {
        Instrument inst = instrument(InstrumentType.etf, "Gold BeES");
        inst.setAssetClass(AssetClass.GOLD);
        when(instrumentRepository.findById(inst.getId())).thenReturn(Optional.of(inst));

        searchService().resolve(new ResolveInstrumentRequest(InstrumentType.etf, "Gold BeES", null, null, null, null, null,
                "INR", inst.getId()));

        verify(classification, never()).classify(any());
        verify(instrumentRepository, never()).save(any());
    }

    @Test
    void aKeyMatchedPickSavesWhenOnlyTheClassificationChanged() {
        Instrument inst = instrument(InstrumentType.mutual_fund, "ABSL Frontline");
        inst.setAmfiCode("119551");
        when(instrumentRepository.findByAmfiCode("119551")).thenReturn(Optional.of(inst));
        when(classification.classify(inst)).thenReturn(true);

        searchService().resolve(new ResolveInstrumentRequest(InstrumentType.mutual_fund, "ABSL Frontline", null, null,
                null, "119551", null, "INR", null));

        verify(instrumentRepository, times(1)).save(inst);
    }

    // ------------------------------------------------------------------ price refresh

    @Test
    void aPriceRefreshClassifiesItsTargetsAndSavesTheChangedOnes() {
        Instrument changed = instrument(InstrumentType.etf, "Gold BeES");
        Instrument same = instrument(InstrumentType.stock, "Reliance");
        Instrument broken = instrument(InstrumentType.stock, "Broken");
        HoldingRepository holdings = mock(HoldingRepository.class);
        when(holdings.findDistinctActiveInstrumentIdsHeld()).thenReturn(List.of(changed.getId(), same.getId(), broken.getId()));
        when(instrumentRepository.findAllById(any())).thenReturn(List.of(changed, same, broken));
        when(classification.classify(changed)).thenReturn(true);
        when(classification.classify(same)).thenReturn(false);
        when(classification.classify(broken)).thenThrow(new IllegalStateException("boom"));
        PriceProvider provider = mock(PriceProvider.class);
        when(provider.supports(any())).thenReturn(true);
        when(provider.fetch(any())).thenReturn(Map.of());

        PriceRefreshResult result = new PriceRefreshService(instrumentRepository, priceRepository, holdings,
                List.of(provider), new PriceProperties(), classification).refresh(Optional.empty());

        verify(instrumentRepository).save(changed);
        verify(instrumentRepository, never()).save(same);
        verify(instrumentRepository, never()).save(broken);
        assertEquals(3, result.failed().size(), "a classification failure never fails the refresh itself");
    }
}
