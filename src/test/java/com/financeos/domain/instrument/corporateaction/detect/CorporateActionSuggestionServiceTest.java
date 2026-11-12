package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.instrument.dto.CorporateActionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CorporateActionSuggestionServiceTest {

    private final CorporateActionSuggestionRepository suggestions = mock(CorporateActionSuggestionRepository.class);
    private final CorporateActionRepository actions = mock(CorporateActionRepository.class);
    private final CorporateActionService actionService = mock(CorporateActionService.class);
    private final DemergerCandidates demergerCandidates = mock(DemergerCandidates.class);
    private final CorporateActionSuggestionService service =
            new CorporateActionSuggestionService(suggestions, actions, actionService, demergerCandidates);

    private final UUID userId = UUID.randomUUID();
    private Instrument acme;
    private CorporateAction action;

    @BeforeEach
    void setUp() {
        UserContext.setCurrentUserId(userId);
        acme = new Instrument();
        acme.setId(UUID.randomUUID());
        acme.setName("Acme Ltd");
        acme.setSymbol("ACME");
        action = new CorporateAction();
        action.setId(UUID.randomUUID());
        when(suggestions.save(any(CorporateActionSuggestion.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CorporateActionSuggestion suggestion(SuggestionStatus status, SuggestionKind kind) {
        CorporateActionSuggestion s = new CorporateActionSuggestion();
        s.setId(UUID.randomUUID());
        s.setInstrument(acme);
        s.setKind(kind);
        s.setStatus(status);
        s.setExDate(LocalDate.of(2026, 10, 1));
        s.setRatioFrom(1);
        s.setRatioTo(2);
        when(suggestions.findOwnedById(s.getId(), userId)).thenReturn(Optional.of(s));
        return s;
    }

    private static CreateCorporateActionRequest request() {
        return new CreateCorporateActionRequest(CorporateActionType.split, 1, 5, LocalDate.of(2026, 10, 1), null, null, null, null);
    }

    @Test
    void undoDeletesTheLinkedActionAndMarksUndone() {
        CorporateActionSuggestion s = suggestion(SuggestionStatus.auto_applied, SuggestionKind.split);
        s.setCorporateAction(action);
        when(actions.findByIdAndUser_Id(action.getId(), userId)).thenReturn(Optional.of(action));

        var response = service.undo(s.getId());

        verify(actionService).deleteCorporateAction(acme.getId(), action.getId());
        assertThat(response.status()).isEqualTo(SuggestionStatus.undone);
        assertThat(response.corporateActionId()).isNull();
        assertThat(s.getResolvedAt()).isNotNull();
    }

    @Test
    void undoContinuesWhenTheActionIsAlreadyGone() {
        CorporateActionSuggestion s = suggestion(SuggestionStatus.auto_applied, SuggestionKind.bonus);
        s.setCorporateAction(action);
        when(actions.findByIdAndUser_Id(action.getId(), userId)).thenReturn(Optional.empty());

        var response = service.undo(s.getId());

        verify(actionService, never()).deleteCorporateAction(any(), any());
        assertThat(response.status()).isEqualTo(SuggestionStatus.undone);
    }

    @Test
    void undoOfAnotherStatusIsRejected() {
        CorporateActionSuggestion s = suggestion(SuggestionStatus.pending, SuggestionKind.split);
        assertThatThrownBy(() -> service.undo(s.getId())).isInstanceOf(ValidationException.class);
        verify(actionService, never()).deleteCorporateAction(any(), any());
    }

    @Test
    void acceptCreatesTheActionForTheSuggestionsInstrumentAndLinksIt() {
        CorporateActionSuggestion s = suggestion(SuggestionStatus.pending, SuggestionKind.split);
        CreateCorporateActionRequest req = request();
        action.setInstrument(acme);
        CorporateActionResponse created = CorporateActionResponse.from(action);
        when(actionService.createCorporateAction(acme.getId(), req)).thenReturn(created);
        when(actions.findByIdAndUser_Id(action.getId(), userId)).thenReturn(Optional.of(action));

        var response = service.accept(s.getId(), req);

        assertThat(response.status()).isEqualTo(SuggestionStatus.accepted);
        assertThat(response.corporateActionId()).isEqualTo(action.getId());
        assertThat(s.getResolvedAt()).isNotNull();
    }

    @Test
    void acceptOfANonPendingOrNonActionKindIsRejected() {
        CorporateActionSuggestion applied = suggestion(SuggestionStatus.auto_applied, SuggestionKind.split);
        CorporateActionSuggestion oversold = suggestion(SuggestionStatus.pending, SuggestionKind.oversold);
        assertThatThrownBy(() -> service.accept(applied.getId(), request())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.accept(oversold.getId(), request())).isInstanceOf(ValidationException.class);
        verify(actionService, never()).createCorporateAction(any(), any());
    }

    @Test
    void dismissSettlesAPendingSuggestionOnly() {
        CorporateActionSuggestion pending = suggestion(SuggestionStatus.pending, SuggestionKind.merger);
        CorporateActionSuggestion applied = suggestion(SuggestionStatus.auto_applied, SuggestionKind.split);

        var response = service.dismiss(pending.getId());

        assertThat(response.status()).isEqualTo(SuggestionStatus.dismissed);
        assertThat(pending.getResolvedAt()).isNotNull();
        assertThatThrownBy(() -> service.dismiss(applied.getId())).isInstanceOf(ValidationException.class);
    }

    @Test
    void anotherUsersSuggestionIsNotFound() {
        UUID foreign = UUID.randomUUID();
        when(suggestions.findOwnedById(foreign, userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(foreign)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.undo(foreign)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.accept(foreign, request())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.dismiss(foreign)).isInstanceOf(ResourceNotFoundException.class);
    }
}
