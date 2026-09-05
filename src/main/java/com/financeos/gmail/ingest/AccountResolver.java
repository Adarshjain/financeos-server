package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class AccountResolver {

    public record ResolvedCard(Account account, Card card) {}

    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final AccountIdentifierRepository accountIdentifierRepository;

    public AccountResolver(AccountRepository accountRepository,
                           CardRepository cardRepository,
                           AccountIdentifierRepository accountIdentifierRepository) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.accountIdentifierRepository = accountIdentifierRepository;
    }

    private boolean isMatch(String ext, String db) {
        if (ext == null || db == null) {
            return false;
        }
        String cleanExt = ext.trim().replaceAll("\\s+", "");
        String cleanDb = db.trim().replaceAll("\\s+", "");
        if (cleanExt.isEmpty() || cleanDb.isEmpty()) {
            return false;
        }
        if (cleanExt.equalsIgnoreCase(cleanDb)) {
            return true;
        }
        if (cleanExt.length() >= 3 && cleanDb.length() >= 3) {
            if (cleanExt.endsWith(cleanDb) || cleanDb.endsWith(cleanExt)) {
                return true;
            }
            if (cleanExt.startsWith(cleanDb) || cleanDb.startsWith(cleanExt)) {
                return true;
            }
            if (cleanExt.toLowerCase().contains(cleanDb.toLowerCase()) || cleanDb.toLowerCase().contains(cleanExt.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isExact(String ext, String db) {
        if (ext == null || db == null) {
            return false;
        }
        String cleanExt = ext.trim().replaceAll("\\s+", "");
        String cleanDb = db.trim().replaceAll("\\s+", "");
        return !cleanExt.isEmpty() && cleanExt.equalsIgnoreCase(cleanDb);
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedCard> resolve(String accountLast4) {
        if (accountLast4 == null || accountLast4.trim().isEmpty()) {
            return Optional.empty();
        }

        List<AccountIdentifier> allAliases = accountIdentifierRepository.findAll();

        // Pass 0: Exact match against aliases only. User-created alias is explicit intent and beats collisions.
        List<ResolvedCard> pass0Matches = new ArrayList<>();
        for (AccountIdentifier alias : allAliases) {
            if (isExact(accountLast4, alias.getValue())) {
                pass0Matches.add(new ResolvedCard(alias.getAccount(), null));
            }
        }
        if (pass0Matches.size() == 1) {
            return Optional.of(pass0Matches.get(0));
        }
        if (pass0Matches.size() > 1) {
            return Optional.empty(); // Ambiguity -> UNRESOLVED_ACCOUNT. Do NOT fall through.
        }

        List<Account> allAccounts = accountRepository.findAll();
        List<Card> allCards = cardRepository.findAll();

        // Pass 1: Exact equality on last 4 digits vs every open card and every account_bank_details.last4
        List<ResolvedCard> pass1Matches = new ArrayList<>();
        for (Account acc : allAccounts) {
            if (acc.getBankDetails() != null && isExact(accountLast4, acc.getBankDetails().getLast4())) {
                pass1Matches.add(new ResolvedCard(acc, null));
            }
        }
        for (Card card : allCards) {
            if (card.isOpen() && (card.getCardholder() == null || !card.getCardholder().isEffectivelyClosed())) {
                if (isExact(accountLast4, card.getLast4())) {
                    pass1Matches.add(new ResolvedCard(card.getAccount(), card));
                }
            }
        }
        if (pass1Matches.size() == 1) {
            return Optional.of(pass1Matches.get(0));
        }
        if (pass1Matches.size() > 1) {
            return Optional.empty(); // Ambiguity -> UNRESOLVED_ACCOUNT
        }

        // Pass 2: Fuzzy isMatch over open cards, bank accounts, and aliases
        List<ResolvedCard> rawPass2Matches = new ArrayList<>();
        for (Account acc : allAccounts) {
            if (acc.getBankDetails() != null && isMatch(accountLast4, acc.getBankDetails().getLast4())) {
                rawPass2Matches.add(new ResolvedCard(acc, null));
            }
        }
        for (Card card : allCards) {
            if (card.isOpen() && (card.getCardholder() == null || !card.getCardholder().isEffectivelyClosed())) {
                if (isMatch(accountLast4, card.getLast4())) {
                    rawPass2Matches.add(new ResolvedCard(card.getAccount(), card));
                }
            }
        }
        for (AccountIdentifier alias : allAliases) {
            if (isMatch(accountLast4, alias.getValue())) {
                rawPass2Matches.add(new ResolvedCard(alias.getAccount(), null));
            }
        }

        // Dedup pass-2 candidates by account id, preferring the entry that carries a card
        Map<UUID, ResolvedCard> pass2ByAccount = new LinkedHashMap<>();
        for (ResolvedCard candidate : rawPass2Matches) {
            UUID accId = candidate.account().getId();
            ResolvedCard existing = pass2ByAccount.get(accId);
            if (existing == null) {
                pass2ByAccount.put(accId, candidate);
            } else if (existing.card() == null && candidate.card() != null) {
                pass2ByAccount.put(accId, candidate);
            }
        }
        List<ResolvedCard> pass2Matches = new ArrayList<>(pass2ByAccount.values());
        if (pass2Matches.size() == 1) {
            return Optional.of(pass2Matches.get(0));
        }
        if (pass2Matches.size() > 1) {
            return Optional.empty(); // Ambiguity -> UNRESOLVED_ACCOUNT
        }

        // Pass 3: Exact match against closed cards or cards of closed cardholders
        List<ResolvedCard> pass3Matches = new ArrayList<>();
        for (Card card : allCards) {
            if (card.isClosed() || (card.getCardholder() != null && card.getCardholder().isEffectivelyClosed())) {
                if (isExact(accountLast4, card.getLast4())) {
                    pass3Matches.add(new ResolvedCard(card.getAccount(), card));
                }
            }
        }
        if (pass3Matches.size() == 1) {
            return Optional.of(pass3Matches.get(0));
        }

        return Optional.empty();
    }
}
