package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.AssetClassOverrideService;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.user.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CorporateActionRecheckTest {

    private static final LocalDate EX = LocalDate.of(2026, 10, 28);

    private final CorporateActionSuggestionRepository suggestions = mock(CorporateActionSuggestionRepository.class);
    private final CorporateActionRepository actions = mock(CorporateActionRepository.class);
    private final OversoldSuggestionService oversold = mock(OversoldSuggestionService.class);
    private final CorporateActionRecheck recheck = new CorporateActionRecheck(suggestions, actions, oversold,
            mock(PlatformTransactionManager.class));
    private final UUID userId = UUID.randomUUID();
    private final Instrument acme = instrument();

    private static Instrument instrument() {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        return i;
    }

    private CorporateActionSuggestion suggestion(SuggestionKind kind, LocalDate ex, SuggestionStatus status) {
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(acme);
        s.setKind(kind);
        s.setExDate(ex);
        s.setStatus(status);
        return s;
    }

    private CorporateAction action(CorporateActionType type, LocalDate ex) {
        CorporateAction ca = new CorporateAction();
        ca.setId(UUID.randomUUID());
        ca.setInstrument(acme);
        ca.setType(type);
        ca.setExDate(ex);
        return ca;
    }

    private void given(List<CorporateActionSuggestion> s, List<CorporateAction> a) {
        when(suggestions.findAllOwned(userId)).thenReturn(s);
        when(actions.findOwnedByInstrumentIds(any(), any())).thenReturn(a);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aRecordedActionOfTheSameInstrumentKindAndExDateClosesThePendingSuggestion() {
        CorporateActionSuggestion s = suggestion(SuggestionKind.split, EX, SuggestionStatus.pending);
        CorporateAction ca = action(CorporateActionType.split, EX);
        given(List.of(s), List.of(ca));

        recheck.recheck(userId);

        assertThat(s.getStatus()).isEqualTo(SuggestionStatus.accepted);
        assertThat(s.getCorporateAction()).isSameAs(ca);
        assertThat(s.getResolvedAt()).isNotNull();
        verify(suggestions).saveAll(any());
        verify(oversold).sync(userId);
    }

    @Test
    void aDifferentExDateOrKindOrAnAlreadyResolvedSuggestionIsLeftAlone() {
        CorporateActionSuggestion otherDate = suggestion(SuggestionKind.split, EX.plusDays(1), SuggestionStatus.pending);
        CorporateActionSuggestion otherKind = suggestion(SuggestionKind.bonus, EX, SuggestionStatus.pending);
        CorporateActionSuggestion dismissed = suggestion(SuggestionKind.split, EX, SuggestionStatus.dismissed);
        given(List.of(otherDate, otherKind, dismissed), List.of(action(CorporateActionType.split, EX)));

        recheck.recheck(userId);

        assertThat(otherDate.getStatus()).isEqualTo(SuggestionStatus.pending);
        assertThat(otherKind.getStatus()).isEqualTo(SuggestionStatus.pending);
        assertThat(dismissed.getStatus()).isEqualTo(SuggestionStatus.dismissed);
        verify(suggestions, never()).saveAll(any());
        verify(oversold).sync(userId);
    }

    @Test
    void aFailureNeverEscapesAndTheOversoldCheckStillRuns() {
        when(suggestions.findAllOwned(userId)).thenThrow(new IllegalStateException("db down"));
        doThrow(new IllegalStateException("boom")).when(oversold).sync(userId);

        assertThatCode(() -> recheck.recheck(userId)).doesNotThrowAnyException();
        assertThatCode(() -> recheck.onChanged(new CorporateActionRecheckEvent(userId))).doesNotThrowAnyException();
        verify(oversold, org.mockito.Mockito.times(2)).sync(userId);
    }

    @Test
    void severalChangesInOneTransactionPublishOneRecheckPerUser() {
        CorporateActionRepository repo = mock(CorporateActionRepository.class);
        InstrumentRepository instruments = mock(InstrumentRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AssetClassOverrideService overrides = mock(AssetClassOverrideService.class);
        when(overrides.overridesForCurrentUser()).thenReturn(InstrumentOverrides.NONE);
        CorporateActionService service = new CorporateActionService(repo, instruments, mock(HoldingRepository.class),
                mock(UserRepository.class), overrides);
        service.setEventPublisher(publisher);
        UUID instrumentId = acme.getId();
        when(instruments.findById(instrumentId)).thenReturn(Optional.of(acme));
        when(repo.save(any(CorporateAction.class))).thenAnswer(inv -> inv.getArgument(0));
        UserContext.setCurrentUserId(userId);
        TransactionSynchronizationManager.initSynchronization();

        service.createCorporateAction(instrumentId, new CreateCorporateActionRequest(CorporateActionType.split, 1, 2, EX, null, null, null, null));
        service.createCorporateAction(instrumentId, new CreateCorporateActionRequest(CorporateActionType.bonus, 1, 2, EX.plusDays(1), null, null, null, null));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publishEvent(events.capture());
        assertThat(events.getAllValues()).containsOnlyOnce(new CorporateActionRecheckEvent(userId));
    }
}
