package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.AccountCard;
import com.financeos.domain.account.card.AccountCardRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class AccountResolver {

    public record ResolvedCard(Account account, AccountCard card) {}

    private final AccountRepository accountRepository;
    private final AccountCardRepository cardRepository;

    public AccountResolver(AccountRepository accountRepository, AccountCardRepository cardRepository) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
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

        List<Account> allAccounts = accountRepository.findAll();
        List<AccountCard> allCards = cardRepository.findAll();

        // Pass 1: Exact equality on last 4 digits vs every open account_cards.last4 and every account_bank_details.last4
        List<ResolvedCard> pass1Matches = new ArrayList<>();
        for (Account acc : allAccounts) {
            if (acc.getBankDetails() != null && isExact(accountLast4, acc.getBankDetails().getLast4())) {
                pass1Matches.add(new ResolvedCard(acc, null));
            }
        }
        for (AccountCard card : allCards) {
            if (card.getClosedOn() == null && isExact(accountLast4, card.getLast4())) {
                pass1Matches.add(new ResolvedCard(card.getAccount(), card));
            }
        }
        if (pass1Matches.size() == 1) {
            return Optional.of(pass1Matches.get(0));
        }
        if (pass1Matches.size() > 1) {
            return Optional.empty(); // Ambiguity -> UNRESOLVED_ACCOUNT
        }

        // Pass 2: Existing isMatch fuzzy logic over open cards and bank accounts
        List<ResolvedCard> pass2Matches = new ArrayList<>();
        for (Account acc : allAccounts) {
            if (acc.getBankDetails() != null && isMatch(accountLast4, acc.getBankDetails().getLast4())) {
                pass2Matches.add(new ResolvedCard(acc, null));
            }
        }
        for (AccountCard card : allCards) {
            if (card.getClosedOn() == null && isMatch(accountLast4, card.getLast4())) {
                pass2Matches.add(new ResolvedCard(card.getAccount(), card));
            }
        }
        if (pass2Matches.size() == 1) {
            return Optional.of(pass2Matches.get(0));
        }
        if (pass2Matches.size() > 1) {
            return Optional.empty(); // Ambiguity -> UNRESOLVED_ACCOUNT
        }

        // Pass 3: Exact match against closed cards
        List<ResolvedCard> pass3Matches = new ArrayList<>();
        for (AccountCard card : allCards) {
            if (card.getClosedOn() != null && isExact(accountLast4, card.getLast4())) {
                pass3Matches.add(new ResolvedCard(card.getAccount(), card));
            }
        }
        if (pass3Matches.size() == 1) {
            return Optional.of(pass3Matches.get(0));
        }

        return Optional.empty();
    }
}
