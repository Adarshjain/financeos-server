package com.financeos.domain.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Per-user asset-class overrides: read by user, upserted, and removed with a null class. */
class AssetClassOverrideServiceTest {

    private final UUID user = UUID.randomUUID();
    private final UUID instrument = UUID.randomUUID();
    private UserInstrumentOverrideRepository repository;
    private AssetClassOverrideService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserInstrumentOverrideRepository.class);
        service = new AssetClassOverrideService(repository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void readsAUsersOverridesByInstrument() {
        UUID other = UUID.randomUUID();
        when(repository.findByUserId(user)).thenReturn(List.of(
                new UserInstrumentOverride(user, instrument, AssetClass.GOLD),
                new UserInstrumentOverride(user, other, AssetClass.DEBT)));
        assertEquals(Map.of(instrument, AssetClass.GOLD, other, AssetClass.DEBT), service.forUser(user));
        UserContext.setCurrentUserId(user);
        assertEquals(Map.of(instrument, AssetClass.GOLD, other, AssetClass.DEBT), service.forCurrentUser());
    }

    @Test
    void readsOneOverride() {
        when(repository.findByUserIdAndInstrumentId(user, instrument))
                .thenReturn(Optional.of(new UserInstrumentOverride(user, instrument, AssetClass.HYBRID)));
        assertEquals(AssetClass.HYBRID, service.forUser(user, instrument));
        assertNull(service.forUser(user, UUID.randomUUID()));
    }

    @Test
    void withoutAUserThereAreNoOverridesAndNoQuery() {
        assertTrue(service.forUser(null).isEmpty());
        assertNull(service.forUser(null, instrument));
        assertTrue(service.forCurrentUser().isEmpty());
        assertNull(service.forCurrentUser(instrument));
        verifyNoInteractions(repository);
    }

    @Test
    void setCreatesOrUpdatesTheUsersRow() {
        service.set(user, instrument, AssetClass.GOLD);
        ArgumentCaptor<UserInstrumentOverride> saved = ArgumentCaptor.forClass(UserInstrumentOverride.class);
        verify(repository).save(saved.capture());
        assertEquals(user, saved.getValue().getUserId());
        assertEquals(instrument, saved.getValue().getInstrumentId());
        assertEquals(AssetClass.GOLD, saved.getValue().getAssetClass());

        UserInstrumentOverride existing = new UserInstrumentOverride(user, instrument, AssetClass.GOLD);
        when(repository.findByUserIdAndInstrumentId(user, instrument)).thenReturn(Optional.of(existing));
        service.set(user, instrument, AssetClass.INTERNATIONAL);
        assertEquals(AssetClass.INTERNATIONAL, existing.getAssetClass());
        verify(repository).save(existing);
    }

    @Test
    void nullRemovesTheOverride() {
        UserInstrumentOverride existing = new UserInstrumentOverride(user, instrument, AssetClass.GOLD);
        when(repository.findByUserIdAndInstrumentId(user, instrument)).thenReturn(Optional.of(existing));
        service.set(user, instrument, null);
        verify(repository).delete(existing);
        verify(repository, never()).save(any());
    }

    @Test
    void nullWithoutAnOverrideIsANoOp() {
        service.set(user, instrument, null);
        verify(repository, never()).delete(any());
        verify(repository, never()).save(any());
    }
}
