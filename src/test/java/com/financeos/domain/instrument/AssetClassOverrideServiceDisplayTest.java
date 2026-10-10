package com.financeos.domain.instrument;

import com.financeos.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The display-field half of {@link AssetClassOverrideService}: edits relative to the catalog, reset, move. */
class AssetClassOverrideServiceDisplayTest {

    private final UUID user = UUID.randomUUID();
    private UserInstrumentOverrideRepository repository;
    private AssetClassOverrideService service;
    private Instrument instrument;

    @BeforeEach
    void setUp() {
        repository = mock(UserInstrumentOverrideRepository.class);
        service = new AssetClassOverrideService(repository);
        instrument = new Instrument();
        instrument.setId(UUID.randomUUID());
        instrument.setType(InstrumentType.stock);
        instrument.setName("Infosys Ltd");
        instrument.setSymbol("INFY");
        instrument.setExchange("NSE");
        instrument.setCurrency("INR");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UserInstrumentOverride saved() {
        ArgumentCaptor<UserInstrumentOverride> captor = ArgumentCaptor.forClass(UserInstrumentOverride.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void valuesThatDifferFromTheCatalogBecomeOverrides() {
        service.setDisplay(user, instrument, "  My Infy ", "infy-x", "BSE", "USD", InstrumentType.etf);
        UserInstrumentOverride row = saved();
        assertEquals(user, row.getUserId());
        assertEquals(instrument.getId(), row.getInstrumentId());
        assertEquals("My Infy", row.getName(), "trimmed");
        assertEquals("infy-x", row.getSymbol());
        assertEquals("BSE", row.getExchange());
        assertEquals("USD", row.getCurrency());
        assertEquals(InstrumentType.etf, row.getType());
        assertNull(row.getAssetClass());
    }

    @Test
    void catalogValuesAndBlanksAreNoOverrideAndCreateNoRow() {
        // Symbol / exchange / currency compare case-insensitively; the name exactly.
        service.setDisplay(user, instrument, "Infosys Ltd", "infy", "nse", " ", InstrumentType.stock);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    void aNameDifferingOnlyInCaseIsAnOverride() {
        service.setDisplay(user, instrument, "INFOSYS LTD", null, null, null, null);
        assertEquals("INFOSYS LTD", saved().getName());
    }

    @Test
    void anEditBackToTheCatalogClearsTheFieldAndKeepsTheAssetClass() {
        UserInstrumentOverride existing = new UserInstrumentOverride(user, instrument.getId(), AssetClass.GOLD);
        existing.setName("Mine");
        when(repository.findByUserIdAndInstrumentId(user, instrument.getId())).thenReturn(Optional.of(existing));

        service.setDisplay(user, instrument, "Infosys Ltd", "INFY", "NSE", "INR", InstrumentType.stock);

        assertNull(existing.getName());
        assertEquals(AssetClass.GOLD, existing.getAssetClass());
        verify(repository).save(existing);
    }

    @Test
    void anEditThatLeavesNothingOverriddenDeletesTheRow() {
        UserInstrumentOverride existing = new UserInstrumentOverride(user, instrument.getId());
        existing.setSymbol("X");
        when(repository.findByUserIdAndInstrumentId(user, instrument.getId())).thenReturn(Optional.of(existing));

        service.setDisplay(user, instrument, "Infosys Ltd", "INFY", null, null, null);

        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void aMissingCatalogCurrencyMeansInr() {
        instrument.setCurrency(null);
        service.setDisplay(user, instrument, "Infosys Ltd", null, null, "inr", null);
        verify(repository, never()).save(any());
    }

    @Test
    void clearingTheAssetClassKeepsDisplayOverrides() {
        UserInstrumentOverride existing = new UserInstrumentOverride(user, instrument.getId(), AssetClass.DEBT);
        existing.setName("Mine");
        when(repository.findByUserIdAndInstrumentId(user, instrument.getId())).thenReturn(Optional.of(existing));

        service.set(user, instrument.getId(), null);

        assertNull(existing.getAssetClass());
        assertEquals("Mine", existing.getName());
        verify(repository).save(existing);
        verify(repository, never()).delete(any());
    }

    @Test
    void settingAnAssetClassKeepsDisplayOverrides() {
        UserInstrumentOverride existing = new UserInstrumentOverride(user, instrument.getId());
        existing.setName("Mine");
        when(repository.findByUserIdAndInstrumentId(user, instrument.getId())).thenReturn(Optional.of(existing));

        service.set(user, instrument.getId(), AssetClass.HYBRID);

        assertEquals(AssetClass.HYBRID, existing.getAssetClass());
        assertEquals("Mine", existing.getName());
    }

    @Test
    void clearRemovesTheRowAndSaysWhetherThereWasOne() {
        UserInstrumentOverride existing = new UserInstrumentOverride(user, instrument.getId(), AssetClass.DEBT);
        when(repository.findByUserIdAndInstrumentId(user, instrument.getId())).thenReturn(Optional.of(existing));
        assertTrue(service.clear(user, instrument.getId()));
        verify(repository).delete(existing);

        assertFalse(service.clear(user, UUID.randomUUID()));
    }

    @Test
    void overridesForReadsOneUsersRowsOrNone() {
        UserInstrumentOverride r = new UserInstrumentOverride(user, instrument.getId());
        r.setName("Mine");
        when(repository.findByUserId(user)).thenReturn(List.of(r));
        assertEquals("Mine", service.overridesFor(user).name(instrument));
        UserContext.setCurrentUserId(user);
        assertEquals("Mine", service.overridesForCurrentUser().name(instrument));
    }

    @Test
    void withoutAUserThereAreNoOverridesAndNoQuery() {
        assertSame(InstrumentOverrides.NONE, service.overridesFor(null));
        assertSame(InstrumentOverrides.NONE, service.overridesForCurrentUser());
        verifyNoInteractions(repository);
    }

    @Test
    void theAssetClassMapSkipsDisplayOnlyRows() {
        UserInstrumentOverride displayOnly = new UserInstrumentOverride(user, instrument.getId());
        displayOnly.setName("Mine");
        when(repository.findByUserId(user)).thenReturn(List.of(displayOnly));
        assertTrue(service.forUser(user).isEmpty());
    }

    @Test
    void moveCarriesTheAssetClassToATargetWithoutOne() {
        UUID target = UUID.randomUUID();
        UserInstrumentOverride source = new UserInstrumentOverride(user, instrument.getId(), AssetClass.GOLD);
        source.setName("Old name");
        when(repository.findByUserIdAndInstrumentId(user, instrument.getId())).thenReturn(Optional.of(source));

        service.moveAssetClass(user, instrument.getId(), target);

        verify(repository).delete(source);
        UserInstrumentOverride created = saved();
        assertEquals(target, created.getInstrumentId());
        assertEquals(AssetClass.GOLD, created.getAssetClass());
        assertNull(created.getName(), "display overrides were relative to the source");
    }

    @Test
    void moveKeepsTheTargetsOwnAssetClass() {
        UUID target = UUID.randomUUID();
        UserInstrumentOverride source = new UserInstrumentOverride(user, instrument.getId(), AssetClass.GOLD);
        UserInstrumentOverride onTarget = new UserInstrumentOverride(user, target, AssetClass.DEBT);
        when(repository.findByUserIdAndInstrumentId(user, instrument.getId())).thenReturn(Optional.of(source));
        when(repository.findByUserIdAndInstrumentId(user, target)).thenReturn(Optional.of(onTarget));

        service.moveAssetClass(user, instrument.getId(), target);

        verify(repository).delete(source);
        assertEquals(AssetClass.DEBT, onTarget.getAssetClass());
        verify(repository, never()).save(any());
    }

    @Test
    void moveWithoutASourceRowDoesNothing() {
        service.moveAssetClass(user, instrument.getId(), UUID.randomUUID());
        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }

    @Test
    void differingTrimsAndComparesAsAsked() {
        assertNull(AssetClassOverrideService.differing(null, "x", true));
        assertNull(AssetClassOverrideService.differing("  ", "x", true));
        assertNull(AssetClassOverrideService.differing(" X ", "x", true));
        assertEquals("X", AssetClassOverrideService.differing(" X ", "x", false));
        assertEquals("y", AssetClassOverrideService.differing("y", null, true));
    }
}
