package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionRepository;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Re-evaluates a user's suggestions right after they change a corporate action, instead of waiting for the nightly
 * detection: a pending detected suggestion that a recorded action now answers (same instrument, same kind, same
 * ex-date: the rule {@code CorporateActionSuggestionRepository.findPendingUnresolved} already uses, plus the kind) is
 * closed as accepted and linked to that action, and the oversold check runs again. No market-feed calls. Runs after
 * commit and never throws, so the user's save is never failed or undone by it.
 */
@Service
public class CorporateActionRecheck {

    private static final Logger log = LoggerFactory.getLogger(CorporateActionRecheck.class);
    private static final Set<SuggestionKind> CLOSABLE = EnumSet.of(SuggestionKind.split, SuggestionKind.bonus,
            SuggestionKind.demerger, SuggestionKind.merger);

    private final CorporateActionSuggestionRepository suggestions;
    private final CorporateActionRepository actions;
    private final OversoldSuggestionService oversold;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public CorporateActionRecheck(CorporateActionSuggestionRepository suggestions, CorporateActionRepository actions,
                                  OversoldSuggestionService oversold,
                                  org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.suggestions = suggestions;
        this.actions = actions;
        this.oversold = oversold;
        this.tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(CorporateActionRecheckEvent event) {
        recheck(event.userId());
    }

    /** Closes the suggestions the user's actions now answer, then re-runs the oversold check; never throws. */
    public void recheck(UUID userId) {
        try {
            tx.executeWithoutResult(status -> closeAnswered(userId));
        } catch (Exception e) {
            log.warn("Closing answered corporate action suggestions failed for user {}: {}", userId, e.toString());
        }
        try {
            // Own transaction: the committed one is still bound in the after-commit callback and may hold stale state.
            tx.executeWithoutResult(status -> oversold.sync(userId));
        } catch (Exception e) {
            log.warn("Oversold recheck failed for user {}: {}", userId, e.toString());
        }
    }

    private void closeAnswered(UUID userId) {
        List<CorporateActionSuggestion> pending = new ArrayList<>();
        for (CorporateActionSuggestion s : suggestions.findAllOwned(userId)) {
            if (s.getStatus() == SuggestionStatus.pending && CLOSABLE.contains(s.getKind())) {
                pending.add(s);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        List<UUID> instrumentIds = pending.stream().map(s -> s.getInstrument().getId()).distinct().toList();
        List<CorporateAction> recorded = actions.findOwnedByInstrumentIds(userId, instrumentIds);
        List<CorporateActionSuggestion> closed = new ArrayList<>();
        for (CorporateActionSuggestion s : pending) {
            for (CorporateAction ca : recorded) {
                if (ca.getInstrument().getId().equals(s.getInstrument().getId()) && ca.getExDate().equals(s.getExDate())
                        && ca.getType() == typeOf(s.getKind())) {
                    s.setStatus(SuggestionStatus.accepted);
                    s.setCorporateAction(ca);
                    s.setResolvedAt(Instant.now());
                    closed.add(s);
                    break;
                }
            }
        }
        if (!closed.isEmpty()) {
            suggestions.saveAll(closed);
        }
    }

    private static CorporateActionType typeOf(SuggestionKind kind) {
        return CorporateActionType.valueOf(kind.name());
    }
}
