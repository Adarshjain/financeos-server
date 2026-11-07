package com.financeos.gmail.accountdetect.suggest;

import com.financeos.api.account.dto.CreateCardRequest;
import com.financeos.api.account.dto.CreateCardholderRequest;
import com.financeos.api.accountdetect.dto.BelongsAs;
import com.financeos.api.accountdetect.dto.BelongsRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderService;
import com.financeos.domain.account.card.PastCardService;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.accountdetect.BankDirectory;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestion.State;
import com.financeos.gmail.ingest.event.AccountIngestChangedEvent;
import com.financeos.gmail.ingest.event.ParkedMailSettleRequestedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * "This belongs to that account": saves a found number on an account the user already has (as an older card number (a closed credit or debit card), an
 * add-on card, another open card, a debit card or an alias), in one transaction with the claim of the suggestion
 * (NEW to BELONGS, atomic). Any failure rolls everything back and the suggestion stays NEW. Parked mail is settled
 * after commit. Every query is filtered by user.
 */
@Service
public class SuggestionBelongsService {

    private final AccountSuggestionRepository repository;
    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final CardholderService cardholderService;
    private final AccountIdentifierService identifierService;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final PastCardService pastCardService;

    public SuggestionBelongsService(AccountSuggestionRepository repository, AccountRepository accountRepository,
                                    CardRepository cardRepository, CardholderService cardholderService,
                                    AccountIdentifierService identifierService, UserRepository userRepository,
                                    ApplicationEventPublisher events, PastCardService pastCardService) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.cardholderService = cardholderService;
        this.identifierService = identifierService;
        this.userRepository = userRepository;
        this.events = events;
        this.pastCardService = pastCardService;
    }

    @Transactional
    public void belongs(UUID userId, UUID id, BelongsRequest req) {
        AccountSuggestion s = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ValidationException("That suggestion was not found."));
        if (s.getState() != State.NEW) throw new ValidationException("This suggestion was already handled");
        Account account = accountRepository.findById(req.accountId())
                .filter(a -> a.getUser() != null && userId.equals(a.getUser().getId()))
                .orElseThrow(() -> new ValidationException("That account was not found."));
        if (account.getBank() == null || !account.getBank().equals(s.getBank())) {
            throw new ValidationException("Only accounts at " + BankDirectory.displayName(s.getBank()) + " can hold this number");
        }
        // an older number is a closed card: a debit card on a bank account, or a credit card on a credit card account
        boolean olderDebit = req.as() == BelongsAs.OLDER_NUMBER && s.getKind() == AccountSuggestion.Kind.DEBIT_CARD;
        if (req.as() == BelongsAs.OLDER_NUMBER && s.getKind() == AccountSuggestion.Kind.BANK_ACCOUNT
                && account.getType() == AccountType.bank_account) {
            throw new ValidationException("An account number can't be an old card.");
        }
        boolean wantsBankAccount = req.as() == BelongsAs.DEBIT_CARD
                || req.as() == BelongsAs.ALIAS || olderDebit;
        AccountType needed = wantsBankAccount ? AccountType.bank_account : AccountType.credit_card;
        if (account.getType() != needed) {
            throw new ValidationException(wantsBankAccount ? "This needs a bank account." : "This needs a credit card account.");
        }
        boolean bankSideNumber = s.getKind() == AccountSuggestion.Kind.BANK_ACCOUNT || s.getKind() == AccountSuggestion.Kind.DEBIT_CARD;
        if (bankSideNumber != wantsBankAccount) {
            throw new ValidationException(bankSideNumber
                    ? "A bank account number can only be a debit card or another number of a bank account."
                    : "A card number can only be saved on a credit card account.");
        }
        if (req.as() == BelongsAs.ADDON && req.relationship() == null) {
            throw new ValidationException("Who holds this add-on card? Choose a relationship.");
        }
        // the claim first: it takes the row lock, so two answers cannot both win
        if (repository.claimBelongs(id, userId, AppTime.nowInstant()) != 1) throw new ValidationException("This suggestion was already handled");

        UUID cardId = switch (req.as()) {
            case OLDER_NUMBER -> olderNumber(account, s, req);
            case ADDON -> addon(account, s, req);
            case OPEN_CARD, DEBIT_CARD -> openCard(account, s);
            case ALIAS -> alias(account, s);
        };

        s.setState(State.BELONGS);
        s.setResolvedAccountId(account.getId());
        s.setResolvedCardId(cardId);
        s.setCandidateAccountId(null);
        repository.save(s);
        events.publishEvent(new ParkedMailSettleRequestedEvent(userId));
    }

    private void requireNoCardWithNumber(Account account, AccountSuggestion s) {
        boolean has = cardRepository.findByAccountId(account.getId()).stream().anyMatch(c -> s.getLast4().equals(c.getLast4()));
        if (has) throw new ValidationException("This account already has a card ending " + s.getLast4() + ".");
    }

    /** A CLOSED card on the primary holder (closed on the last mail's date, else today). The active card is untouched. */
    private UUID olderNumber(Account account, AccountSuggestion s, BelongsRequest req) {
        requireNoCardWithNumber(account, s);
        if (req.pastCard() != null) {
            // the user's dates and holder, through the shared past-card path (held-elsewhere guard, parked-mail reactivation)
            return pastCardService.addForNumber(account.getUser(), account, s.getLast4(), req.pastCard(), s.getBank()).card().getId();
        }
        pastCardService.requireNotHeldElsewhere(account.getUser(), account, s.getLast4());
        LocalDate today = AppTime.today();
        LocalDate closedOn = s.getLastSeenOn() != null && !s.getLastSeenOn().isAfter(today) ? s.getLastSeenOn() : today;
        LocalDate issuedOn = s.getFirstSeenOn() != null && !s.getFirstSeenOn().isAfter(closedOn) ? s.getFirstSeenOn() : null;
        Cardholder primary = account.primaryCardholder().orElse(null);
        if (primary == null) {
            // no holder yet: the primary is created with this card, which is then closed (the holder has no active card)
            cardholderService.addPrimaryWithCard(account.getId(), new CreateCardRequest(s.getLast4(), issuedOn));
            Card card = cardRepository.findOpenByAccountIdAndLast4(account.getId(), s.getLast4()).orElseThrow();
            // the new primary defaults its card to issued today; an older number was issued before it closed
            if (card.getIssuedOn() != null && card.getIssuedOn().isAfter(closedOn)) card.setIssuedOn(issuedOn);
            card.close(closedOn);
            cardRepository.save(card);
            return card.getId();
        }
        Card card = new Card();
        card.setUser(userRepository.getReferenceById(account.getUser().getId()));
        card.setAccount(account);
        card.setCardholder(primary);
        card.setLast4(s.getLast4());
        card.setIssuedOn(issuedOn);
        card.setClosedOn(closedOn);
        cardRepository.save(card);
        primary.getCards().add(card);
        events.publishEvent(new AccountIngestChangedEvent(account.getUser().getId(), card.getLast4(), account.getIngestFromDate()));
        return card.getId();
    }

    private UUID addon(Account account, AccountSuggestion s, BelongsRequest req) {
        requireNoCardWithNumber(account, s);
        String name = req.holderName() == null || req.holderName().isBlank() ? null : req.holderName().trim();
        cardholderService.addAddon(account.getId(), new CreateCardholderRequest(name, req.relationship(), null, s.getLast4(), null, null));
        return openCardId(account, s);
    }

    /**
     * Another open card on the primary holder. The holder keeps one active card at a time, so when it already has one this
     * is refused (a clear 400) rather than replacing it behind the user's back: replacing is a separate, explicit answer.
     */
    private UUID openCard(Account account, AccountSuggestion s) {
        requireNoCardWithNumber(account, s);
        Cardholder primary = account.primaryCardholder().orElse(null);
        if (primary == null) {
            cardholderService.addPrimaryWithCard(account.getId(), new CreateCardRequest(s.getLast4(), null));
        } else {
            primary.openCard().ifPresent(active -> {
                throw new ValidationException("The primary holder of this account already has an active card ending "
                        + active.getLast4() + ". Replace that card instead, or save this number as an older number.");
            });
            cardholderService.addCard(account.getId(), primary.getId(), new CreateCardRequest(s.getLast4(), null));
        }
        return openCardId(account, s);
    }

    private UUID openCardId(Account account, AccountSuggestion s) {
        return cardRepository.findOpenByAccountIdAndLast4(account.getId(), s.getLast4()).map(Card::getId).orElse(null);
    }

    private UUID alias(Account account, AccountSuggestion s) {
        identifierService.createOrUpdateIdentifier(account.getUser(), account, s.getLast4(), AccountIdentifierKind.ACCOUNT_NUMBER);
        return null;
    }
}
