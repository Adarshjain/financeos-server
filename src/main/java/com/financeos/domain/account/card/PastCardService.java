package com.financeos.domain.account.card;

import com.financeos.api.account.dto.PastCardRequest;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.User;
import com.financeos.gmail.attention.NumberGroupKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Saves a number the app saw as an old card of an account, then settles the parked mail that names it the way adding
 * an identifier does (same bank scoping and counts). Callers check the account is the user's.
 */
@Service
public class PastCardService {

    public record Result(Card card, int reactivatedCount, List<UUID> jobIds) {}

    private final CardholderService cardholderService;
    private final AccountIdentifierService identifierService;
    private final AccountIdentifierRepository identifierRepository;
    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;

    public PastCardService(CardholderService cardholderService, AccountIdentifierService identifierService,
                           AccountIdentifierRepository identifierRepository, AccountRepository accountRepository,
                           CardRepository cardRepository) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.cardholderService = cardholderService;
        this.identifierService = identifierService;
        this.identifierRepository = identifierRepository;
    }

    /**
     * Mail for a number settles into whichever account the resolver finds for it, so a number another account of the user
     * already holds (open card, bank last 4 or identifier; an account at a different bank does not count) cannot become an
     * old card here: the mail would land on the other account.
     */
    private void requireNotHeldElsewhere(User user, Account account, String last4) {
        String holder = null;
        for (Card c : cardRepository.findByUserIdAndLast4(user.getId(), last4)) {
            Account other = c.getAccount();
            if (c.isOpen() && !other.getId().equals(account.getId()) && !c.getCardholder().isEffectivelyClosed()
                    && NumberGroupKey.attachable(account.getBank(), other.getBank())) {
                holder = other.getName();
            }
        }
        for (Account other : accountRepository.findByUserId(user.getId())) {
            if (!other.getId().equals(account.getId()) && other.getBankDetails() != null
                    && last4.equals(other.getBankDetails().getLast4()) && NumberGroupKey.attachable(account.getBank(), other.getBank())) {
                holder = other.getName();
            }
        }
        var identifier = identifierRepository.findByUserIdAndValue(user.getId(), last4).orElse(null);
        if (identifier != null && !identifier.getAccount().getId().equals(account.getId())) {
            holder = identifier.getAccount().getName();
        }
        if (holder != null) {
            throw new ValidationException("••" + last4 + " already belongs to " + holder);
        }
    }

    /** @param groupBank when set, only that bank's parked mail with these digits is settled */
    @Transactional
    public Result addForNumber(User user, Account account, String last4, PastCardRequest request, String groupBank) {
        requireNotHeldElsewhere(user, account, last4);
        Card card = cardholderService.createPastCard(user, account, request.cardholderId(), last4, request.issuedOn(), request.closedOn());
        AccountIdentifierService.Reactivation r = identifierService.reactivateParked(user, account, last4, groupBank);
        return new Result(card, r.reactivatedCount(), r.jobIds());
    }

    /** The account's identifier becomes an old card: the identifier goes and the card comes, in one transaction. */
    @Transactional
    public Result convertIdentifier(User user, Account account, UUID identifierId, PastCardRequest request) {
        AccountIdentifier identifier = identifierRepository.findByIdAndAccountId(identifierId, account.getId())
                .filter(i -> i.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("AccountIdentifier", identifierId));
        String last4 = identifier.getValue();
        if (last4 == null || !last4.matches("^[0-9]{4}$")) {
            throw new ValidationException("Only a 4-digit number can be saved as an old card.");
        }
        requireNotHeldElsewhere(user, account, last4);
        Card card = cardholderService.createPastCard(user, account, request.cardholderId(), last4, request.issuedOn(), request.closedOn());
        identifierRepository.delete(identifier);
        identifierRepository.flush();
        AccountIdentifierService.Reactivation r = identifierService.reactivateParked(user, account, last4, account.getBank());
        return new Result(card, r.reactivatedCount(), r.jobIds());
    }
}
