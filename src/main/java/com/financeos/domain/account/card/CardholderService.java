package com.financeos.domain.account.card;

import com.financeos.api.account.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.reward.RewardMilestoneRepository;
import com.financeos.domain.reward.RewardRuleRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.ingest.event.AccountIngestChangedEvent;
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

    public CardholderService(CardholderRepository cardholderRepository,
                             CardRepository cardRepository,
                             AccountRepository accountRepository,
                             UserRepository userRepository,
                             TransactionRepository transactionRepository,
                             RewardRuleRepository rewardRuleRepository,
                             RewardMilestoneRepository rewardMilestoneRepository,
                             ApplicationEventPublisher eventPublisher) {
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

    public CardholderResponse addPrimaryWithCard(UUID accountId, CreateCardRequest request) {
        Account account = getAccountAndValidateAccess(accountId);
        if (account.isClosed()) {
            throw new ValidationException("Cannot add cardholder to a closed account. Reopen the account first.");
        }

        List<Cardholder> existingCardholders = cardholderRepository.findByAccountId(accountId);
        if (existingCardholders.stream().anyMatch(Cardholder::isPrimary)) {
            throw new ValidationException("This account already has a primary cardholder. Issue a card to it instead.");
        }

        Optional<Card> existing = cardRepository.findOpenByAccountIdAndLast4(accountId, request.last4());
        if (existing.isPresent()) {
            throw new ValidationException("A card with last 4 digits " + request.last4() + " is already active on this account.");
        }

        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Cardholder cardholder = new Cardholder();
        cardholder.setUser(user);
        cardholder.setAccount(account);
        cardholder.setRole(CardholderRole.PRIMARY);
        cardholder.setPersonName(null);
        cardholder.setRelationship(CardholderRelationship.SELF);
        cardholder.setSpendLimit(null);
        LocalDate openedOn = request.issuedOn() != null ? request.issuedOn() : LocalDate.now();
        cardholder.setOpenedOn(openedOn);

        Cardholder savedCh = cardholderRepository.save(cardholder);

        Card card = new Card();
        card.setUser(user);
        card.setAccount(account);
        card.setCardholder(savedCh);
        card.setLast4(request.last4());
        card.setIssuedOn(request.issuedOn() != null ? request.issuedOn() : openedOn);
        cardRepository.save(card);
        savedCh.getCards().add(card);

        eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, card.getLast4(), account.getIngestFromDate()));

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
        if (account.isClosed()) {
            throw new ValidationException("Cannot add cardholder to a closed account. Reopen the account first.");
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
        LocalDate openedOn = request.openedOn() != null ? request.openedOn() : LocalDate.now();
        cardholder.setOpenedOn(openedOn);

        Cardholder savedCh = cardholderRepository.save(cardholder);

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
            cardRepository.save(card);
            savedCh.getCards().add(card);

            eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, card.getLast4(), account.getIngestFromDate()));
        }

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

        cardholder.setClosedOn(closedOn != null ? closedOn : LocalDate.now());
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

    public CardholderResponse addCard(UUID accountId, UUID cardholderId, CreateCardRequest request) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
        Account account = cardholder.getAccount();
        if (account.isClosed()) {
            throw new ValidationException("Cannot add card to a closed account. Reopen the account first.");
        }
        if (cardholder.isEffectivelyClosed()) {
            throw new ValidationException("Cannot add card to a closed cardholder. Reopen the cardholder first.");
        }
        if (cardholder.openCard().isPresent()) {
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
        card.setIssuedOn(request.issuedOn() != null ? request.issuedOn() : LocalDate.now());

        cardRepository.save(card);
        cardholder.getCards().add(0, card);

        eventPublisher.publishEvent(new AccountIngestChangedEvent(userId, card.getLast4(), account.getIngestFromDate()));

        long count = 0;
        for (Card c : cardholder.getCards()) {
            count += transactionRepository.countByCardId(c.getId());
        }
        return CardholderResponse.from(cardholder, count);
    }

    public CardholderResponse replaceCard(UUID accountId, UUID cardholderId, UUID cardId, ReplaceCardRequest request) {
        Cardholder cardholder = getCardholderAndValidateAccess(accountId, cardholderId);
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

        LocalDate issuedOn = request.issuedOn() != null ? request.issuedOn() : LocalDate.now();
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

        card.close(closedOn != null ? closedOn : LocalDate.now());
        cardRepository.save(card);

        long count = 0;
        for (Card c : cardholder.getCards()) {
            count += transactionRepository.countByCardId(c.getId());
        }
        return CardholderResponse.from(cardholder, count);
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
