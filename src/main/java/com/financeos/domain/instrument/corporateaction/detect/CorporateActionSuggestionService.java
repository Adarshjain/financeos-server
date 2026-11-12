package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.api.instrument.dto.CorporateActionSuggestionResponse;
import com.financeos.api.instrument.dto.CreateCorporateActionRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionService;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The user's answers to detected corporate actions: look, undo an automatic one, accept or dismiss a held one. */
@Service
@Transactional
public class CorporateActionSuggestionService {

    /** The kinds a user can accept: the ones that map to a corporate action type. */
    static final Set<SuggestionKind> ACCEPTABLE = Set.of(SuggestionKind.split, SuggestionKind.bonus,
            SuggestionKind.demerger, SuggestionKind.merger, SuggestionKind.other);

    private final CorporateActionSuggestionRepository suggestions;
    private final CorporateActionRepository actions;
    private final CorporateActionService actionService;
    private final DemergerCandidates demergerCandidates;

    public CorporateActionSuggestionService(CorporateActionSuggestionRepository suggestions,
                                            CorporateActionRepository actions,
                                            CorporateActionService actionService,
                                            DemergerCandidates demergerCandidates) {
        this.suggestions = suggestions;
        this.actions = actions;
        this.actionService = actionService;
        this.demergerCandidates = demergerCandidates;
    }

    private static UUID requireUser() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ValidationException("A corporate action suggestion needs a signed-in user");
        }
        return userId;
    }

    private CorporateActionSuggestion own(UUID userId, UUID id) {
        return suggestions.findOwnedById(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("CorporateActionSuggestion", id));
    }

    @Transactional(readOnly = true)
    public CorporateActionSuggestionResponse get(UUID id) {
        CorporateActionSuggestion s = own(requireUser(), id);
        return CorporateActionSuggestionResponse.from(s, demergerCandidates.forSuggestion(s));
    }

    /** Removes the action the detector applied (through the normal delete) and marks the suggestion undone. */
    public CorporateActionSuggestionResponse undo(UUID id) {
        UUID userId = requireUser();
        CorporateActionSuggestion s = own(userId, id);
        if (s.getStatus() != SuggestionStatus.auto_applied) {
            throw new ValidationException("Only an automatically applied action can be undone");
        }
        CorporateAction linked = s.getCorporateAction();
        if (linked != null) {
            UUID actionId = linked.getId();
            s.setCorporateAction(null);
            // Already gone (deleted by hand): nothing to remove. Checked first so no failed call marks the transaction.
            if (actions.findByIdAndUser_Id(actionId, userId).isPresent()) {
                actionService.deleteCorporateAction(s.getInstrument().getId(), actionId);
            }
        }
        return resolve(s, SuggestionStatus.undone);
    }

    /** Creates the action from the user's own entries for the held suggestion's instrument and links it. */
    public CorporateActionSuggestionResponse accept(UUID id, CreateCorporateActionRequest request) {
        UUID userId = requireUser();
        CorporateActionSuggestion s = own(userId, id);
        if (s.getStatus() != SuggestionStatus.pending || !ACCEPTABLE.contains(s.getKind())) {
            throw new ValidationException("Only a pending corporate action can be accepted");
        }
        var created = actionService.createCorporateAction(s.getInstrument().getId(), request);
        actions.findByIdAndUser_Id(created.id(), userId).ifPresent(s::setCorporateAction);
        return resolve(s, SuggestionStatus.accepted);
    }

    public CorporateActionSuggestionResponse dismiss(UUID id) {
        CorporateActionSuggestion s = own(requireUser(), id);
        if (s.getStatus() != SuggestionStatus.pending) {
            throw new ValidationException("Only a pending corporate action can be dismissed");
        }
        return resolve(s, SuggestionStatus.dismissed);
    }

    private CorporateActionSuggestionResponse resolve(CorporateActionSuggestion s, SuggestionStatus status) {
        s.setStatus(status);
        s.setResolvedAt(Instant.now());
        return CorporateActionSuggestionResponse.from(suggestions.save(s));
    }
}
