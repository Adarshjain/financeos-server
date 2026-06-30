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

    @Transactional(readOnly = true)
    public Optional<Account> resolve(String accountLast4) {
        if (accountLast4 != null && !accountLast4.trim().isEmpty()) {
            List<Account> accounts = accountRepository.findByLast4(accountLast4.trim());
            if (accounts.size() == 1) {
                return Optional.of(accounts.get(0));
            }
        }
        return Optional.empty();
    }
}
