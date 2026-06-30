package com.financeos.gmail.ingest;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Component
public class AccountResolver {

    private final AccountRepository accountRepository;

    public AccountResolver(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
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
        }
        return false;
    }

    @Transactional(readOnly = true)
    public Optional<Account> resolve(String accountLast4) {
        if (accountLast4 == null || accountLast4.trim().isEmpty()) {
            return Optional.empty();
        }
        List<Account> allAccounts = accountRepository.findAll();
        List<Account> matches = allAccounts.stream()
                .filter(acc -> {
                    String bankLast4 = acc.getBankDetails() != null ? acc.getBankDetails().getLast4() : null;
                    String ccLast4 = acc.getCreditCardDetails() != null ? acc.getCreditCardDetails().getLast4() : null;
                    return isMatch(accountLast4, bankLast4) || isMatch(accountLast4, ccLast4);
                })
                .toList();

        if (matches.size() == 1) {
            return Optional.of(matches.get(0));
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<Account> resolve(String accountLast4, GmailSender sender) {
        Optional<Account> resolved = resolve(accountLast4);
        if (resolved.isPresent()) {
            return resolved;
        }
        if (sender != null && sender.getAccount() != null) {
            return Optional.of(sender.getAccount());
        }
        return Optional.empty();
    }


}
