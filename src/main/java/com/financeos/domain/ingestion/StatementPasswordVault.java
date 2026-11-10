package com.financeos.domain.ingestion;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.StatementPasswordSavedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * A user's saved statement passwords (one per account, stored encrypted on the account): lists them so a locked
 * file can be tried against all of them, and saves a password that opened a file. Passwords never leave this
 * class except to the parser; nothing here logs them.
 */
@Service
public class StatementPasswordVault {

    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StatementPasswordVault(AccountRepository accountRepository, ApplicationEventPublisher eventPublisher) {
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Every distinct saved statement password of the user, in account order. */
    @Transactional(readOnly = true)
    public List<String> savedPasswords(UUID userId) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Account a : accountRepository.findByUserIdAndHasStatementPassword(userId)) {
            String pwd = passwordOf(a);
            if (pwd != null && !pwd.isBlank()) {
                out.add(pwd);
            }
        }
        return new ArrayList<>(out);
    }

    /** Saves {@code password} on the account only when it has none; true when saved. */
    @Transactional
    public boolean saveIfMissing(UUID userId, UUID accountId, String password) {
        return store(userId, accountId, password, false);
    }

    /** Saves {@code password} on the account, replacing a different one; true when it changed. */
    @Transactional
    public boolean save(UUID userId, UUID accountId, String password) {
        return store(userId, accountId, password, true);
    }

    private boolean store(UUID userId, UUID accountId, String password, boolean replace) {
        if (password == null || password.isBlank()) {
            return false;
        }
        Account account = accountRepository.findByIdAndUser_Id(accountId, userId).orElse(null);
        if (account == null) {
            return false;
        }
        String current = passwordOf(account);
        boolean has = current != null && !current.isBlank();
        if (has && (!replace || current.equals(password))) {
            return false;
        }
        if (account.getCreditCardDetails() != null) {
            account.getCreditCardDetails().setStatementPassword(password);
        } else if (account.getBankDetails() != null) {
            account.getBankDetails().setStatementPassword(password);
        } else {
            return false;
        }
        accountRepository.save(account);
        eventPublisher.publishEvent(new StatementPasswordSavedEvent(userId));
        return true;
    }

    static String passwordOf(Account a) {
        if (a.getCreditCardDetails() != null && a.getCreditCardDetails().getStatementPassword() != null) {
            return a.getCreditCardDetails().getStatementPassword();
        }
        return a.getBankDetails() != null ? a.getBankDetails().getStatementPassword() : null;
    }
}
