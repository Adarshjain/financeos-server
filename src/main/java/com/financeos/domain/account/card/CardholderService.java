package com.financeos.domain.account.card;

import com.financeos.api.account.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.ingest.event.AccountIngestChangedEvent;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion;
import com.financeos.gmail.accountdetect.suggest.SuggestionAdoption;
import com.financeos.gmail.ingest.event.ParkedMailSettleRequestedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class CardholderService {

    private final CardholderRepository cardholderRepository;
    private final CardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final RewardMilestoneRepository rewardMilestoneRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SuggestionAdoption suggestionAdoption;

    /** Without suggestion support, for callers that build the service by hand. */
    public CardholderService(CardholderRepository cardholderRepository,
                             CardRepository cardRepository,
                             AccountRepository accountRepository,
                             UserRepository userRepository,
                             TransactionRepository transactionRepository,
                             RewardRuleRepository rewardRuleRepository,
                             RewardMilestoneRepository rewardMilestoneRepository,
                             ApplicationEventPublisher eventPublisher) {
        this(cardholderRepository, cardRepository, accountRepository, userRepository, transactionRepository,
                rewardRuleRepository, rewardMilestoneRepository, eventPublisher, null);
    }

    @Autowired
    public CardholderService(CardholderRepository cardholderRepository,
                             CardRepository cardRepository,
                             AccountRepository accountRepository,
                             UserRepository userRepository,
                             TransactionRepository transactionRepository,
                             RewardRuleRepository rewardRuleRepository,
                             RewardMilestoneRepository rewardMilestoneRepository,
                             ApplicationEventPublisher eventPublisher,
                             SuggestionAdoption suggestionAdoption) {
        this.suggestionAdoption = suggestionAdoption;
        this.cardholderRepository = cardholderRepository;
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.rewardMilestoneRepository = rewardMilestoneRepository;
        this.eventPublisher = eventPublisher;
    }

    private Account getAccountAndValidateAccess(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !account.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        if (account.getType() != AccountType.credit_card && account.getType() != AccountType.bank_account) {
            throw new ValidationException("Card operations are only supported on credit card and bank accounts.");
        }
        return account;
    }

    /** The user's NEW suggestion a request names, checked to belong to {@code accountId}; null when it names none. */
    private AccountSuggestion claimSuggestion(UUID suggestionId, UUID accountId, String last4) {
        return suggestionId == null ? null
                : suggestionAdoption.claimForAccount(UserContext.getCurrentUserId(), suggestionId, accountId, last4);
    }

    /** Marks the suggestion ADDED with the new card and asks for parked mail to be settled once the transaction commits. */
    private void adopt(AccountSuggestion suggestion, UUID accountId, Card card) {
        if (suggestion == null) return;
        suggestionAdoption.resolve(suggestion, accountId, card == null ? null : card.getId());
        eventPublisher.publishEvent(new ParkedMailSettleRequestedEvent(UserContext.getCurrentUserId()));
    }

    public CardholderResponse addPrimaryWithCard(UUID accountId, CreateCardRequest request) {
        Account account = getAccountAndValidateAccess(accountId);
        AccountSuggestion suggestion = claimSuggestion(request.fromSuggestionId(), accountId, request.last4());
        if (account.isClosed()) {
            throw new ValidationException("Cannot add cardholder to a closed account. Reopen the account first.");
        }

        // A primary holder without an open card (created by a past card) takes the first card instead of being refused.
        Cardholder existingPrimary = cardholderRepository.findByAccountId(accountId).stream()
                .filter(Cardholder::isPrimary).findFirst().orElse(null);
        if (existingPrimary != null && existingPrimary.openCard().isPresent()) {
            throw new ValidationException("This account already has a primary cardholder. Issue a card to it instead.");
        }

        Optional<Card> existing = cardRepository.findOpenByAccountIdAndLast4(accountId, request.last4());
        if (existing.isPresent()) {
            throw new ValidationException("A card with last 4 digits " + request.last4() + " is already active on this account.");
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        LocalDate openedOn = request.issuedOn() != null ? request.issuedOn() : AppTime.today();
        Cardholder savedCh;
        if (existingPrimary != null) {
            savedCh = existingPrimary;
        } else {
            Cardholder cardholder = new Cardholder();
            cardholder.setUser(user);
            cardholder.setAccount(account);
            cardholder.setRole(CardholderRole.PRIMARY);
            cardholder.setPersonName(null);
            cardholder.setRelationship(CardholderRelationship.SELF);
            cardholder.setSpendLimit(null);
            cardholder.setOpenedOn(openedOn);
            savedCh = cardholderRepository.save(cardholder);
        }

        Card card = new Card();
        card.setUser(user);
        card.setAccount(account);
        card.setCardholder(savedCh);
        card.setLast4(request.last4());
        card.setIssuedOn(request.issuedOn() != null ? request.issuedOn() : openedOn);
        cardRepository.save(card);
        savedCh.getCards().add(card);

        eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, card.getLast4(), account.getIngestFromDate()));
        adopt(suggestion, accountId, card);

        return CardholderResponse.from(savedCh, 0L);
    }

    private Cardholder getCardholderAndValidateAccess(UUID accountId, UUID cardholderId) {
        getAccountAndValidateAccess(accountId);
        Cardholder cardholder = cardholderRepository.findByIdAndAccountId(cardholderId, accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Cardholder", cardholderId));
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !cardholder.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this cardholder.");
        }
        return cardholder;
    }

    @Transactional(readOnly = true)
    public List<CardholderResponse> listByAccount(UUID accountId) {
        Account account = getAccountAndValidateAccess(accountId);
        List<Cardholder> cardholders = cardholderRepository.findByAccountId(accountId);
        return cardholders.stream().map(ch -> {
            long count = 0;
            if (ch.getCards() != null) {
                for (Card c : ch.getCards()) {
                    count += transactionRepository.countByCardId(c.getId());
                }
            }
            return CardholderResponse.from(ch, count);
        }).toList();
    }

    public CardholderResponse addAddon(UUID accountId, CreateCardholderRequest request) {
        Account account = getAccountAndValidateAccess(accountId);
        AccountSuggestion suggestion = claimSuggestion(request.fromSuggestionId(), accountId, request.last4());
        boolean closedHolder = request.closedOn() != null;
        if (account.isClosed() && !closedHolder) {
            throw new ValidationException("Cannot add cardholder to a closed account. Reopen the account first.");
        }
        if (closedHolder) {
            if (request.last4() == null || request.last4().isBlank()) {
                throw new ValidationException("A closed cardholder needs the card number it held.");
            }
            validatePastCardDates(account, null, request.issuedOn(), request.closedOn());
            if (request.openedOn() != null && request.openedOn().isAfter(request.closedOn())) {
                throw new ValidationException("Opened on cannot be after the closed date.");
            }
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Cardholder cardholder = new Cardholder();
        cardholder.setUser(user);
        cardholder.setAccount(account);
        cardholder.setRole(CardholderRole.ADDON);
        cardholder.setPersonName(request.personName());
        cardholder.setRelationship(request.relationship() != null ? request.relationship() : CardholderRelationship.OTHER);
        cardholder.setSpendLimit(request.spendLimit());
        LocalDate openedOn = request.openedOn() != null ? request.openedOn()
                : closedHolder ? request.issuedOn() : AppTime.today();
        cardholder.setOpenedOn(openedOn);
        if (closedHolder) {
            cardholder.setClosedOn(request.closedOn());
        }

        Cardholder savedCh = cardholderRepository.save(cardholder);

        Card addedCard = null;
        if (request.last4() != null && !request.last4().isBlank()) {
            Optional<Card> existing = cardRepository.findOpenByAccountIdAndLast4(accountId, request.last4());
            if (existing.isPresent()) {
                throw new ValidationException("A card with last 4 digits " + request.last4() + " is already active on this account.");
            }
            Card card = new Card();
            card.setUser(user);
            card.setAccount(account);
            card.setCardholder(savedCh);
            card.setLast4(request.last4());
            card.setIssuedOn(request.issuedOn() != null ? request.issuedOn() : openedOn);
            if (closedHolder) {
                card.setClosedOn(request.closedOn());
            }
            cardRepository.save(card);
            savedCh.getCards().add(card);
            addedCard = card;

            eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, card.getLast4(), account.getIngestFromDate()));
        }
        adopt(suggestion, accountId, addedCard);

        return CardholderResponse.from(savedCh, 0L);
    }

    public CardholderResponse updateCardholder(UUID accountId, UUID cardholderId, UpdateCardholderRequest request) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);

        if (cardholder.isPrimary()) {
            if (request.relationship() != null && request.relationship() != CardholderRelationship.SELF) {
                throw new ValidationException("Primary cardholder relationship must be SELF.");
            }
        } else {
            if (request.relationship() != null) {
                cardholder.setRelationship(request.relationship());
            }
        }

        if (request.openedOn() != null) {
            if (request.openedOn().isAfter(AppTime.today())) {
                throw new ValidationException("Opened on cannot be in the future.");
            }
            if (cardholder.getClosedOn() != null && request.openedOn().isAfter(cardholder.getClosedOn())) {
                throw new ValidationException("Opened on cannot be after the closed date.");
            }
            cardholder.setOpenedOn(request.openedOn());
        }

        cardholder.setPersonName(request.personName());
        cardholder.setSpendLimit(request.spendLimit());

        Cardholder saved = cardholderRepository.save(cardholder);
        long count = 0;
        if (saved.getCards() != null) {
            for (Card c : saved.getCards()) {
                count += transactionRepository.countByCardId(c.getId());
            }
        }
        return CardholderResponse.from(saved, count);
    }

    public CardholderResponse closeCardholder(UUID accountId, UUID cardholderId, LocalDate closedOn) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        if (cardholder.isPrimary()) {
            throw new ValidationException("Primary cardholder cannot be closed. To retire this cardholder, close the account instead.");
        }

        cardholder.setClosedOn(closedOn != null ? closedOn : AppTime.today());
        Cardholder saved = cardholderRepository.save(cardholder);

        long count = 0;
        if (saved.getCards() != null) {
            for (Card c : saved.getCards()) {
                count += transactionRepository.countByCardId(c.getId());
            }
        }
        return CardholderResponse.from(saved, count);
    }

    public CardholderResponse reopenCardholder(UUID accountId, UUID cardholderId) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        if (cardholder.isPrimary()) {
            throw new ValidationException("Primary cardholder cannot be reopened directly.");
        }
        if (cardholder.getAccount().isClosed()) {
            throw new ValidationException("Cannot reopen cardholder on a closed account. Reopen the account first.");
        }

        Card openCard = cardholder.openCard().orElse(null);
        if (openCard != null) {
            Optional<Card> collision = cardRepository.findOpenByAccountIdAndLast4(accountId, openCard.getLast4());
            if (collision.isPresent() && !collision.get().getId().equals(openCard.getId())) {
                throw new ValidationException("Cannot reopen cardholder: card with last 4 digits " + openCard.getLast4() + " collides with an open card on this account.");
            }
        }

        cardholder.setClosedOn(null);
        Cardholder saved = cardholderRepository.save(cardholder);

        long count = 0;
        if (saved.getCards() != null) {
            for (Card c : saved.getCards()) {
                count += transactionRepository.countByCardId(c.getId());
            }
        }
        return CardholderResponse.from(saved, count);
    }

    /**
     * A past card needs both dates, issued on or before closed, closed on or before today, and, on a closed account or
     * for a closed holder, closed no later than the day that account or holder closed.
     */
    private void validatePastCardDates(Account account, Cardholder cardholder, LocalDate issuedOn, LocalDate closedOn) {
        if (issuedOn == null) {
            throw new ValidationException("A past card needs the date it was issued.");
        }
        if (closedOn.isAfter(AppTime.today())) {
            throw new ValidationException("Closed on cannot be in the future.");
        }
        if (issuedOn.isAfter(closedOn)) {
            throw new ValidationException("Issued on cannot be after the closed date.");
        }
        if (account.isClosed() && closedOn.isAfter(account.getClosedOn())) {
            throw new ValidationException("Closed on cannot be after the account closed on " + account.getClosedOn() + ".");
        }
        if (cardholder != null && cardholder.isEffectivelyClosed() && closedOn.isAfter(cardholder.effectiveClosedOn())) {
            throw new ValidationException("Closed on cannot be after the cardholder closed on " + cardholder.effectiveClosedOn() + ".");
        }
    }

    /**
     * Saves a number as a closed card of an account the caller has already checked is the user's: on the given holder
     * (must belong to the account), else the primary holder, which is created (opened on the card's issue date) when the
     * account has none. Parked mail is not touched here; the caller settles it. Allowed on closed accounts and holders.
     */
    public Card createPastCard(User user, Account account, UUID cardholderId, String last4, LocalDate issuedOn, LocalDate closedOn) {
        if (account.getType() != AccountType.credit_card && account.getType() != AccountType.bank_account) {
            throw new ValidationException("Cards can only be added to credit card and bank accounts.");
        }
        if (last4 == null || !last4.matches("^[0-9]{4}$")) {
            throw new ValidationException("last4 must be exactly 4 digits");
        }
        if (closedOn == null) {
            throw new ValidationException("A past card needs the date it was closed.");
        }
        Cardholder cardholder;
        if (cardholderId != null) {
            cardholder = cardholderRepository.findByIdAndAccountId(cardholderId, account.getId())
                    .filter(h -> h.getUser().getId().equals(user.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Cardholder", cardholderId));
        } else {
            cardholder = cardholderRepository.findByAccountId(account.getId()).stream().filter(Cardholder::isPrimary).findFirst().orElse(null);
        }
        validatePastCardDates(account, cardholder, issuedOn, closedOn);
        if (cardRepository.findOpenByAccountIdAndLast4(account.getId(), last4).isPresent()) {
            throw new ValidationException("A card with last 4 digits " + last4 + " is already active on this account.");
        }
        if (cardholder == null) {
            cardholder = new Cardholder();
            cardholder.setUser(user);
            cardholder.setAccount(account);
            cardholder.setRole(CardholderRole.PRIMARY);
            cardholder.setRelationship(CardholderRelationship.SELF);
            cardholder.setOpenedOn(issuedOn);
            cardholder = cardholderRepository.save(cardholder);
        } else if (cardholder.getCards().stream().anyMatch(c -> last4.equals(c.getLast4())
                && issuedOn.equals(c.getIssuedOn()) && closedOn.equals(c.getClosedOn()))) {
            throw new ValidationException("This card is already on the account.");
        }
        Card card = new Card();
        card.setUser(user);
        card.setAccount(account);
        card.setCardholder(cardholder);
        card.setLast4(last4);
        card.setIssuedOn(issuedOn);
        card.setClosedOn(closedOn);
        cardRepository.save(card);
        cardholder.getCards().add(0, card);
        return card;
    }

    public CardholderResponse addCard(UUID accountId, UUID cardholderId, CreateCardRequest request) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        AccountSuggestion suggestion = claimSuggestion(request.fromSuggestionId(), accountId, request.last4());
        Account account = cardholder.getAccount();
        boolean pastCard = request.closedOn() != null;
        if (account.isClosed() && !pastCard) {
            throw new ValidationException("Cannot add card to a closed account. Reopen the account first.");
        }
        if (cardholder.isEffectivelyClosed() && !pastCard) {
            throw new ValidationException("Cannot add card to a closed cardholder. Reopen the cardholder first.");
        }
        if (pastCard) {
            validatePastCardDates(account, cardholder, request.issuedOn(), request.closedOn());
        } else if (cardholder.openCard().isPresent()) {
            throw new ValidationException("Cardholder already has an active card. To issue a new card number, replace it instead.");
        }

        Optional<Card> existing = cardRepository.findOpenByAccountIdAndLast4(accountId, request.last4());
        if (existing.isPresent()) {
            throw new ValidationException("A card with last 4 digits " + request.last4() + " is already active on this account.");
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Card card = new Card();
        card.setUser(user);
        card.setAccount(account);
        card.setCardholder(cardholder);
        card.setLast4(request.last4());
        card.setIssuedOn(request.issuedOn() != null ? request.issuedOn() : AppTime.today());
        if (pastCard) {
            card.setClosedOn(request.closedOn());
        }

        cardRepository.save(card);
        cardholder.getCards().add(0, card);

        // Parked mail with these digits settles now: inside the account's window it is reprocessed, older mail is skipped.
        eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, card.getLast4(), account.getIngestFromDate()));
        adopt(suggestion, accountId, card);

        long count = 0;
        for (Card c : cardholder.getCards()) {
            count += transactionRepository.countByCardId(c.getId());
        }
        return CardholderResponse.from(cardholder, count);
    }

    public CardholderResponse replaceCard(UUID accountId, UUID cardholderId, UUID cardId, ReplaceCardRequest request) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        AccountSuggestion suggestion = claimSuggestion(request.fromSuggestionId(), accountId, request.newLast4());
        Account account = cardholder.getAccount();
        if (account.isClosed()) {
            throw new ValidationException("Cannot replace card on a closed account. Reopen the account first.");
        }
        if (cardholder.isEffectivelyClosed()) {
            throw new ValidationException("Cannot replace card on a closed cardholder. Reopen the cardholder first.");
        }

        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));
        if (!card.getCardholder().getId().equals(cardholderId)) {
            throw new ValidationException("Card does not belong to this cardholder.");
        }
        if (card.isClosed()) {
            throw new ValidationException("Card is already closed.");
        }

        LocalDate issuedOn = request.issuedOn() != null ? request.issuedOn() : AppTime.today();
        card.close(issuedOn);
        // LOAD-BEARING: saveAndFlush vacates uq_card_open and uq_card_open_last4 so issuer reissue with same last 4 succeeds
        cardRepository.saveAndFlush(card);

        Optional<Card> collision = cardRepository.findOpenByAccountIdAndLast4(accountId, request.newLast4());
        if (collision.isPresent()) {
            throw new ValidationException("A card with last 4 digits " + request.newLast4() + " is already active on this account.");
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Card newCard = new Card();
        newCard.setUser(user);
        newCard.setAccount(account);
        newCard.setCardholder(cardholder);
        newCard.setLast4(request.newLast4());
        newCard.setIssuedOn(issuedOn);

        cardRepository.save(newCard);
        cardholder.getCards().add(0, newCard);

        eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, newCard.getLast4(), account.getIngestFromDate()));
        adopt(suggestion, accountId, newCard);

        long count = 0;
        for (Card c : cardholder.getCards()) {
            count += transactionRepository.countByCardId(c.getId());
        }
        return CardholderResponse.from(cardholder, count);
    }

    public CardholderResponse closeCard(UUID accountId, UUID cardholderId, UUID cardId, LocalDate closedOn) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));
        if (!card.getCardholder().getId().equals(cardholderId)) {
            throw new ValidationException("Card does not belong to this cardholder.");
        }
        if (card.isClosed()) {
            throw new ValidationException("Card is already closed.");
        }

        card.close(closedOn != null ? closedOn : AppTime.today());
        cardRepository.save(card);

        long count = 0;
        for (Card c : cardholder.getCards()) {
            count += transactionRepository.countByCardId(c.getId());
        }
        return CardholderResponse.from(cardholder, count);
    }

    /**
     * Deletes a card that is no longer the holder's current one (closed or replaced). Its transactions move to
     * the holder's open card, else to the most recently issued other card; the only card cannot be deleted.
     */
    public void deleteCard(UUID accountId, UUID cardholderId, UUID cardId) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        Card card = cardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card", cardId));
        if (!card.getCardholder().getId().equals(cardholderId)) {
            throw new ValidationException("Card does not belong to this cardholder.");
        }
        if (card.isOpen()) {
            throw new ValidationException("Close or replace this card first.");
        }

        List<Card> others = cardholder.getCards().stream().filter(c -> !c.getId().equals(cardId)).toList();
        Card target = others.stream().filter(Card::isOpen).findFirst()
                .or(() -> others.stream().max(Comparator
                        .comparing(Card::getIssuedOn, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Card::getCreatedAt)))
                .orElseThrow(() -> new ValidationException("This is the holder's only card. Delete the cardholder instead."));

        transactionRepository.reassignCard(card, target);
        // drop it from the holder too, so the cascade does not re-persist it
        cardholder.getCards().removeIf(c -> c.getId().equals(cardId));
        cardRepository.delete(card);
    }

    public void deleteCardholder(UUID accountId, UUID cardholderId) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        if (cardholder.isPrimary()) {
            throw new ValidationException("Primary cardholder cannot be deleted.");
        }

        long txCount = 0;
        if (cardholder.getCards() != null) {
            for (Card c : cardholder.getCards()) {
                txCount += transactionRepository.countByCardId(c.getId());
            }
        }
        if (txCount > 0) {
            throw new ValidationException("Cannot delete cardholder with transaction history. Close the cardholder instead.");
        }

        long ruleCount = rewardRuleRepository.countByCardholderId(cardholderId);
        if (ruleCount > 0) {
            throw new ValidationException("Cannot delete cardholder referenced by reward rules.");
        }

        long msCount = rewardMilestoneRepository.countByCardholderId(cardholderId);
        if (msCount > 0) {
            throw new ValidationException("Cannot delete cardholder referenced by reward milestones.");
        }

        cardholderRepository.delete(cardholder);
    }
}
