package com.financeos.gmail.accountdetect.suggest;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.Kind;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The "create from suggestion" side of account and card creation: claims the suggestion the request names, atomically
 * and inside the caller's transaction, then records the account and card it became. Kept apart from
 * {@link AccountSuggestionService} so the account and cardholder services can use it without a dependency cycle.
 * Every query is filtered by user.
 */
@Component
public class SuggestionAdoption {

    private static final String ALREADY_HANDLED = "This suggestion was already handled";

    private final AccountSuggestionRepository repository;

    public SuggestionAdoption(AccountSuggestionRepository repository) {
        this.repository = repository;
    }

    /**
     * Claims the user's own NEW suggestion (NEW to ADDED by one conditional UPDATE: exactly one row, else 400). A missing
     * or someone else's suggestion is a 400 that reveals nothing. The claim lives and dies with the caller's transaction.
     */
    public AccountSuggestion claim(UUID userId, UUID suggestionId) {
        return claim(userId, suggestionId, null, null);
    }

    /**
     * {@link #claim}, plus, for a card added to an existing account: the suggestion's parent account must be
     * {@code accountId} and the new card's number must be the suggestion's (a blank number never matches).
     */
    public AccountSuggestion claimForAccount(UUID userId, UUID suggestionId, UUID accountId, String last4) {
        return claim(userId, suggestionId, accountId, last4 == null ? "" : last4.trim());
    }

    private AccountSuggestion claim(UUID userId, UUID suggestionId, UUID accountId, String last4) {
        AccountSuggestion s = repository.findByIdAndUserId(suggestionId, userId)
                .orElseThrow(() -> new ValidationException("That suggestion was not found."));
        if (s.getState() != State.NEW) throw new ValidationException(ALREADY_HANDLED);
        if (accountId != null) {
            if (s.getParentAccountId() == null || !s.getParentAccountId().equals(accountId)) {
                throw new ValidationException("This suggestion belongs to a different account.");
            }
            if (!s.getLast4().equals(last4)) throw new ValidationException("The card number doesn't match the suggestion");
        }
        if (repository.claimNew(suggestionId, userId, AppTime.nowInstant()) != 1) throw new ValidationException(ALREADY_HANDLED);
        s.setState(State.ADDED);   // the bulk update bypassed the loaded copy
        return s;
    }

    /** Records what the claimed suggestion became; a card id is kept only for the card kinds. */
    public void resolve(AccountSuggestion s, UUID accountId, UUID cardId) {
        s.setState(State.ADDED);
        s.setResolvedAccountId(accountId);
        s.setResolvedCardId(s.getKind() == Kind.BANK_ACCOUNT ? null : cardId);
        s.setCandidateAccountId(null);
        repository.save(s);
    }

    /**
     * Marks the NEW children of {@code parent} ADDED when the account's cards include theirs, matched by role: an
     * add-on child by an open add-on holder's card; a debit or replacement child by an open card of the primary holder.
     * Closed (earlier) cards never count.
     */
    public void markChildrenAdded(UUID userId, AccountSuggestion parent, UUID accountId,
                                  Map<String, UUID> openPrimaryCards, Map<String, UUID> openAddonCards) {
        if (openPrimaryCards.isEmpty() && openAddonCards.isEmpty()) return;
        List<AccountSuggestion> children = repository.findByUserIdAndState(userId, State.NEW).stream()
                .filter(c -> parent.getId().equals(c.getParentSuggestionId())).toList();
        for (AccountSuggestion c : children) {
            Map<String, UUID> pool = switch (c.getKind()) {
                case ADDON_CARD -> openAddonCards;
                case DEBIT_CARD, REPLACEMENT_CARD -> openPrimaryCards;
                default -> Map.of();
            };
            UUID cardId = pool.get(c.getLast4());
            if (cardId == null) continue;
            if (repository.claimNew(c.getId(), userId, AppTime.nowInstant()) != 1) continue;
            resolve(c, accountId, cardId);
        }
    }
}
