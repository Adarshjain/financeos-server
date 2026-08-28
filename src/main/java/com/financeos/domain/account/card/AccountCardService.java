package com.financeos.domain.account.card;

import com.financeos.api.account.dto.AccountCardResponse;
import com.financeos.api.account.dto.CloseCardRequest;
import com.financeos.api.account.dto.CreateAccountCardRequest;
import com.financeos.api.account.dto.UpdateAccountCardRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.ingest.event.AccountIngestChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class AccountCardService {

    private final AccountCardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AccountCardService(AccountCardRepository cardRepository,
                              AccountRepository accountRepository,
                              UserRepository userRepository,
                              ApplicationEventPublisher eventPublisher) {
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<AccountCardResponse> getCardsForAccount(UUID accountId) {
        Account account = getValidatedCreditCardAccount(accountId);
        List<AccountCard> cards = cardRepository.findByAccountIdOrderByIsPrimaryDescCreatedAtAsc(account.getId());
        return cards.stream()
                .map(c -> AccountCardResponse.from(c, cardRepository.countTransactionsByCardId(c.getId())))
                .toList();
    }

    public AccountCardResponse createCard(UUID accountId, CreateAccountCardRequest request) {
        Account account = getValidatedCreditCardAccount(accountId);
        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        validateLast4UniqueAmongOpenCards(account.getId(), request.last4(), null);

        AccountCard card = new AccountCard();
        card.setUser(user);
        card.setAccount(account);
        card.setLabel(request.label());
        card.setHolderName(request.holderName());
        card.setRelationship(request.relationship() != null ? request.relationship() : CardRelationship.SELF);
        card.setLast4(request.last4());
        card.setIssuedOn(request.issuedOn());
        card.setSpendLimit(request.spendLimit());
        card.setNote(request.note());
        card.setPrimary(false);

        AccountCard saved = cardRepository.save(card);

        if (saved.getLast4() != null) {
            eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, saved.getLast4(), account.getIngestFromDate()));
        }

        return AccountCardResponse.from(saved, 0L);
    }

    public AccountCardResponse updateCard(UUID accountId, UUID cardId, UpdateAccountCardRequest request) {
        Account account = getValidatedCreditCardAccount(accountId);
        AccountCard card = getValidatedCard(account.getId(), cardId);

        String oldLast4 = card.getLast4();
        if (!Objects.equals(oldLast4, request.last4())) {
            validateLast4UniqueAmongOpenCards(account.getId(), request.last4(), cardId);
        }

        if (request.issuedOn() != null) {
            Optional<LocalDate> earliestTxn = cardRepository.findEarliestTransactionDateByCardId(cardId);
            if (earliestTxn.isPresent() && request.issuedOn().isAfter(earliestTxn.get())) {
                throw new ValidationException("Issue date (" + request.issuedOn() + ") cannot be after the earliest transaction on this card (" + earliestTxn.get() + ")");
            }
            if (card.getClosedOn() != null && request.issuedOn().isAfter(card.getClosedOn())) {
                throw new ValidationException("Issue date cannot be after closing date");
            }
        }

        card.setLabel(request.label());
        card.setHolderName(request.holderName());
        card.setRelationship(request.relationship() != null ? request.relationship() : CardRelationship.SELF);
        card.setLast4(request.last4());
        card.setIssuedOn(request.issuedOn());
        card.setSpendLimit(request.spendLimit());
        card.setNote(request.note());

        AccountCard saved = cardRepository.save(card);

        if (!Objects.equals(oldLast4, saved.getLast4()) && saved.getLast4() != null) {
            eventPublisher.publishEvent(new AccountIngestChangedEvent(saved.getUser().getId(), saved.getLast4(), account.getIngestFromDate()));
        }

        long txnCount = cardRepository.countTransactionsByCardId(saved.getId());
        return AccountCardResponse.from(saved, txnCount);
    }

    public AccountCardResponse closeCard(UUID accountId, UUID cardId, CloseCardRequest request) {
        Account account = getValidatedCreditCardAccount(accountId);
        AccountCard card = getValidatedCard(account.getId(), cardId);

        if (card.getClosedOn() != null) {
            long txnCount = cardRepository.countTransactionsByCardId(card.getId());
            return AccountCardResponse.from(card, txnCount);
        }

        List<AccountCard> openCards = cardRepository.findOpenByAccountId(account.getId());
        if (card.isPrimary() && openCards.size() > 1) {
            throw new ValidationException("Primary card cannot be closed while other open cards exist. Promote another card to primary first.");
        }

        LocalDate closedOn = (request != null && request.closedOn() != null) ? request.closedOn() : LocalDate.now();

        if (card.getIssuedOn() != null && closedOn.isBefore(card.getIssuedOn())) {
            throw new ValidationException("Closing date (" + closedOn + ") cannot precede issue date (" + card.getIssuedOn() + ")");
        }

        Optional<LocalDate> earliestTxn = cardRepository.findEarliestTransactionDateByCardId(cardId);
        if (earliestTxn.isPresent() && closedOn.isBefore(earliestTxn.get())) {
            throw new ValidationException("Closing date (" + closedOn + ") cannot precede the earliest transaction on this card (" + earliestTxn.get() + ")");
        }

        card.setClosedOn(closedOn);
        AccountCard saved = cardRepository.save(card);

        long txnCount = cardRepository.countTransactionsByCardId(saved.getId());
        return AccountCardResponse.from(saved, txnCount);
    }

    public AccountCardResponse reopenCard(UUID accountId, UUID cardId) {
        Account account = getValidatedCreditCardAccount(accountId);
        AccountCard card = getValidatedCard(account.getId(), cardId);

        if (card.getClosedOn() == null) {
            long txnCount = cardRepository.countTransactionsByCardId(card.getId());
            return AccountCardResponse.from(card, txnCount);
        }

        validateLast4UniqueAmongOpenCards(account.getId(), card.getLast4(), cardId);

        card.setClosedOn(null);
        AccountCard saved = cardRepository.save(card);

        long txnCount = cardRepository.countTransactionsByCardId(saved.getId());
        return AccountCardResponse.from(saved, txnCount);
    }

    public AccountCardResponse setPrimary(UUID accountId, UUID cardId) {
        Account account = getValidatedCreditCardAccount(accountId);
        AccountCard targetCard = getValidatedCard(account.getId(), cardId);

        if (targetCard.getClosedOn() != null) {
            throw new ValidationException("Cannot make a closed card primary");
        }

        if (targetCard.isPrimary()) {
            long txnCount = cardRepository.countTransactionsByCardId(targetCard.getId());
            return AccountCardResponse.from(targetCard, txnCount);
        }

        // Demote incumbent primary card(s)
        List<AccountCard> cards = cardRepository.findByAccountIdOrderByIsPrimaryDescCreatedAtAsc(account.getId());
        for (AccountCard c : cards) {
            if (c.isPrimary()) {
                c.setPrimary(false);
                cardRepository.save(c);
            }
        }

        targetCard.setPrimary(true);
        AccountCard saved = cardRepository.save(targetCard);

        long txnCount = cardRepository.countTransactionsByCardId(saved.getId());
        return AccountCardResponse.from(saved, txnCount);
    }

    public void deleteCard(UUID accountId, UUID cardId) {
        Account account = getValidatedCreditCardAccount(accountId);
        AccountCard card = getValidatedCard(account.getId(), cardId);

        if (card.isPrimary()) {
            throw new ValidationException("Primary card cannot be deleted.");
        }

        long txnCount = cardRepository.countTransactionsByCardId(cardId);
        if (txnCount > 0) {
            throw new ValidationException("Cannot delete card with " + txnCount + " transaction(s). Close the card instead.");
        }

        long ruleCount = cardRepository.countRewardRulesByCardId(cardId);
        if (ruleCount > 0) {
            throw new ValidationException("Cannot delete card referenced by " + ruleCount + " reward rule(s). Remove the card from the rule or close the card instead.");
        }

        long milestoneCount = cardRepository.countRewardMilestonesByCardId(cardId);
        if (milestoneCount > 0) {
            throw new ValidationException("Cannot delete card referenced by " + milestoneCount + " reward milestone(s). Remove the card from the milestone or close the card instead.");
        }

        cardRepository.delete(card);
    }

    private void validateLast4UniqueAmongOpenCards(UUID accountId, String last4, UUID excludeCardId) {
        Optional<AccountCard> existing = cardRepository.findOpenByAccountIdAndLast4(accountId, last4);
        if (existing.isPresent() && (excludeCardId == null || !existing.get().getId().equals(excludeCardId))) {
            String conflictingName = existing.get().getLabel() != null ? existing.get().getLabel() : "Existing card";
            throw new ValidationException("Card with last 4 digits " + last4 + " already exists on this account (" + conflictingName + ")");
        }
    }

    private Account getValidatedCreditCardAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !account.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Cards can only be managed for credit card accounts");
        }
        return account;
    }

    private AccountCard getValidatedCard(UUID accountId, UUID cardId) {
        AccountCard card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("AccountCard", cardId));
        if (!card.getAccount().getId().equals(accountId)) {
            throw new ValidationException("Card does not belong to the specified account");
        }
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !card.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this card.");
        }
        return card;
    }
}
