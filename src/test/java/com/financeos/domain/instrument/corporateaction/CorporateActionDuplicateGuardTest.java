package com.financeos.domain.instrument.corporateaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.user.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CorporateActionDuplicateGuardTest {

    private static final LocalDate EX = LocalDate.of(2026, 10, 28);

    private final CorporateActionRepository actions = mock(CorporateActionRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final CorporateActionService service = new CorporateActionService(actions, instruments,
            mock(HoldingRepository.class), mock(UserRepository.class), mock(AssetClassOverrideService.class));
    private final UUID userId = UUID.randomUUID();
    private final UUID instrumentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        Instrument acme = new Instrument();
        acme.setId(instrumentId);
        when(instruments.findById(instrumentId)).thenReturn(Optional.of(acme));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createRejectsTheSameInstrumentTypeAndDate() {
        when(actions.existsSameDay(userId, instrumentId, CorporateActionType.split, EX)).thenReturn(true);
        CreateCorporateActionRequest req = new CreateCorporateActionRequest(CorporateActionType.split, 1, 5, EX, null, null, null, null);

        assertThatThrownBy(() -> service.createCorporateAction(instrumentId, req))
                .isInstanceOf(ValidationException.class)
                .hasMessage("This action is already recorded for that date");
        verify(actions, never()).save(any());
    }

    @Test
    void updateRejectsAnotherActionOnTheSameDay() {
        UUID id = UUID.randomUUID();
        CorporateAction existing = new CorporateAction();
        existing.setId(id);
        existing.setInstrument(new Instrument() {{ setId(instrumentId); }});
        when(actions.findByIdAndUser_Id(id, userId)).thenReturn(Optional.of(existing));
        when(actions.existsOtherSameDay(userId, instrumentId, CorporateActionType.bonus, EX, id)).thenReturn(true);
        UpdateCorporateActionRequest req = new UpdateCorporateActionRequest(CorporateActionType.bonus, 1, 2, EX, null, null, null, null);

        assertThatThrownBy(() -> service.updateCorporateAction(instrumentId, id, req))
                .isInstanceOf(ValidationException.class)
                .hasMessage("This action is already recorded for that date");
        verify(actions, never()).save(any());
    }

    @Test
    void createDetectedSkipsSilentlyWhenAlreadyRecorded() {
        when(actions.existsSameDay(userId, instrumentId, CorporateActionType.split, EX)).thenReturn(true);

        assertThat(service.createDetected(userId, instrumentId, CorporateActionType.split, 1, 5, EX, "n")).isNull();
        verify(actions, never()).save(any());
    }

    @Test
    void demergersOfOneStockOnOneDateIntoDifferentTargetsAreNotDuplicates() {
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        Instrument tb = new Instrument();
        tb.setId(targetB);
        when(instruments.findById(targetB)).thenReturn(Optional.of(tb));
        Instrument ta = new Instrument();
        ta.setId(targetA);
        when(instruments.findById(targetA)).thenReturn(Optional.of(ta));
        when(instruments.existsById(targetA)).thenReturn(true);
        when(instruments.existsById(targetB)).thenReturn(true);
        when(actions.existsSameDayWithTarget(userId, instrumentId, CorporateActionType.demerger, EX, targetA)).thenReturn(true);
        when(actions.save(any())).thenAnswer(i -> i.getArgument(0));
        CreateCorporateActionRequest same = new CreateCorporateActionRequest(CorporateActionType.demerger, 1, 1, EX, null,
                targetA, new java.math.BigDecimal("30"), null);
        CreateCorporateActionRequest other = new CreateCorporateActionRequest(CorporateActionType.demerger, 1, 1, EX, null,
                targetB, new java.math.BigDecimal("30"), null);

        assertThatThrownBy(() -> service.createCorporateAction(instrumentId, same)).isInstanceOf(ValidationException.class);
        try {
            service.createCorporateAction(instrumentId, other);
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException ignored) {
            // later steps of create need more wiring; only the duplicate guard matters here
        }
        verify(actions).existsSameDayWithTarget(userId, instrumentId, CorporateActionType.demerger, EX, targetB);
        verify(actions, never()).existsSameDay(any(), any(), any(), any());
    }
}
