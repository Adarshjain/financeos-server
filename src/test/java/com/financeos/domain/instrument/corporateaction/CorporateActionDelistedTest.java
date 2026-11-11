package com.financeos.domain.instrument.corporateaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.api.instrument.dto.UpdateCorporateActionRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.user.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CorporateActionDelistedTest {

    private static final LocalDate EX = LocalDate.of(2026, 10, 28);

    private final CorporateActionRepository actions = mock(CorporateActionRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final AssetClassOverrideService overrides = mock(AssetClassOverrideService.class);
    private final CorporateActionService service = new CorporateActionService(actions, instruments,
            mock(HoldingRepository.class), mock(UserRepository.class), overrides);
    private final UUID userId = UUID.randomUUID();
    private final UUID instrumentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        Instrument acme = new Instrument();
        acme.setId(instrumentId);
        acme.setName("Acme Ltd");
        when(instruments.findById(instrumentId)).thenReturn(Optional.of(acme));
        when(actions.save(any())).thenAnswer(returnsFirstArg());
        when(overrides.overridesForCurrentUser()).thenReturn(InstrumentOverrides.NONE);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CreateCorporateActionRequest create(Integer from, Integer to, UUID target, java.math.BigDecimal pct) {
        return new CreateCorporateActionRequest(CorporateActionType.delisted, from, to, EX, null, target, pct, null);
    }

    @Test
    void ratiosAreStoredAsOneToOneWhateverTheRequestSends() {
        service.createCorporateAction(instrumentId, create(3, 7, null, null));

        ArgumentCaptor<CorporateAction> saved = ArgumentCaptor.forClass(CorporateAction.class);
        verify(actions).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(CorporateActionType.delisted);
        assertThat(saved.getValue().getRatioFrom()).isEqualTo(1);
        assertThat(saved.getValue().getRatioTo()).isEqualTo(1);
        assertThat(saved.getValue().getTargetInstrument()).isNull();
        assertThat(saved.getValue().getCostAllocationPct()).isNull();
    }

    @Test
    void aTargetInstrumentIsRejected() {
        assertThatThrownBy(() -> service.createCorporateAction(instrumentId, create(1, 1, UUID.randomUUID(), null)))
                .isInstanceOf(ValidationException.class);
        verify(actions, never()).save(any());
    }

    @Test
    void aCostSplitIsRejected() {
        assertThatThrownBy(() -> service.createCorporateAction(instrumentId, create(1, 1, null, new java.math.BigDecimal("40"))))
                .isInstanceOf(ValidationException.class);
        verify(actions, never()).save(any());
    }

    @Test
    void onlyOneDelistingPerInstrumentAndUser() {
        when(actions.existsOfType(userId, instrumentId, CorporateActionType.delisted)).thenReturn(true);

        assertThatThrownBy(() -> service.createCorporateAction(instrumentId, create(1, 1, null, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A delisting is already recorded for this instrument");
        verify(actions, never()).save(any());
    }

    @Test
    void editingToDelistedWhileAnotherExistsIsRejectedAndRatiosResetOtherwise() {
        UUID id = UUID.randomUUID();
        CorporateAction existing = new CorporateAction();
        existing.setId(id);
        existing.setInstrument(new Instrument() {{ setId(instrumentId); }});
        when(actions.findByIdAndUser_Id(id, userId)).thenReturn(Optional.of(existing));
        UpdateCorporateActionRequest req = new UpdateCorporateActionRequest(CorporateActionType.delisted, 2, 9, EX, null, null, null, null);

        when(actions.existsOtherOfType(userId, instrumentId, CorporateActionType.delisted, id)).thenReturn(true);
        assertThatThrownBy(() -> service.updateCorporateAction(instrumentId, id, req)).isInstanceOf(ValidationException.class);

        when(actions.existsOtherOfType(userId, instrumentId, CorporateActionType.delisted, id)).thenReturn(false);
        service.updateCorporateAction(instrumentId, id, req);
        assertThat(existing.getRatioFrom()).isEqualTo(1);
        assertThat(existing.getRatioTo()).isEqualTo(1);
    }

    @Test
    void otherTypesKeepTheirRatios() {
        service.createCorporateAction(instrumentId, new CreateCorporateActionRequest(CorporateActionType.split, 1, 5, EX, null, null, null, null));

        ArgumentCaptor<CorporateAction> saved = ArgumentCaptor.forClass(CorporateAction.class);
        verify(actions).save(saved.capture());
        assertThat(saved.getValue().getRatioTo()).isEqualTo(5);
    }
}
