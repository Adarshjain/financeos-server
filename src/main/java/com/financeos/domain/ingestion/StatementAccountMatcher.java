package com.financeos.domain.ingestion;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.Cardholder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Finds the user's account a statement belongs to: same bank id and the same last 4 of the account number. */
@Component
public class StatementAccountMatcher {

    public static final Set<AccountType> STATEMENT_ACCOUNT_TYPES = EnumSet.of(AccountType.bank_account, AccountType.credit_card);
    public static final String STATEMENT_TYPE_MESSAGE = "Statements can only be uploaded to bank or credit card accounts.";

    /** An account reduced to what matching needs. */
    public record AccountKey(UUID id, String bank, Set<String> last4s) {}

    private final AccountRepository accountRepository;

    public StatementAccountMatcher(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public static void requireStatementAccount(Account account) {
        if (!STATEMENT_ACCOUNT_TYPES.contains(account.getType())) {
            throw new ValidationException(STATEMENT_TYPE_MESSAGE);
        }
    }

    /** The user's bank and card accounts (open or closed) with their bank id and every last 4 they carry. */
    @Transactional(readOnly = true)
    public List<AccountKey> keysOf(UUID userId) {
        List<AccountKey> keys = new java.util.ArrayList<>();
        for (AccountType type : STATEMENT_ACCOUNT_TYPES) {
            for (Account a : accountRepository.findByUserIdAndType(userId, type)) {
                Set<String> last4s = new LinkedHashSet<>();
                if (a.getBankDetails() != null && a.getBankDetails().getLast4() != null) {
                    last4s.add(normalise(a.getBankDetails().getLast4()));
                }
                if (a.getCardholders() != null) {
                    for (Cardholder ch : a.getCardholders()) {
                        if (ch.getCards() == null) continue;
                        for (Card c : ch.getCards()) {
                            if (c.getLast4() != null) last4s.add(normalise(c.getLast4()));
                        }
                    }
                }
                last4s.remove("");
                keys.add(new AccountKey(a.getId(), a.getBank(), last4s));
            }
        }
        return keys;
    }

    /** Ids of the accounts with this bank id carrying this last 4 (empty when either is unknown). */
    public static List<UUID> match(List<AccountKey> keys, String bankId, String last4) {
        if (bankId == null || bankId.isBlank() || last4 == null || last4.isBlank()) {
            return List.of();
        }
        return keys.stream()
                .filter(k -> bankId.equalsIgnoreCase(k.bank()) && k.last4s().contains(normalise(last4)))
                .map(AccountKey::id)
                .toList();
    }

    /** The last 4 letters or digits of an account number as printed ("XXXX 1234" gives "1234"); null when it has fewer than 4. */
    public static String lastFour(String accountNumber) {
        if (accountNumber == null) {
            return null;
        }
        String compact = normalise(accountNumber);
        return compact.length() < 4 ? null : compact.substring(compact.length() - 4);
    }

    private static String normalise(String s) {
        return s.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
