package com.financeos.gmail.accountdetect.unlock;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Loads what the unlocker builds passwords from, for one user (explicit user id: used from jobs). */
@Component
public class UnlockProfileLoader {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    public UnlockProfileLoader(UserRepository userRepository, AccountRepository accountRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public UnlockProfile load(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        Map<String, List<String>> saved = new HashMap<>();
        for (Account a : accountRepository.findByUserIdAndHasStatementPassword(userId)) {
            if (a.getBank() == null) continue;
            String pwd = a.getCreditCardDetails() != null ? a.getCreditCardDetails().getStatementPassword()
                    : a.getBankDetails() != null ? a.getBankDetails().getStatementPassword() : null;
            if (pwd == null || pwd.isBlank()) continue;
            List<String> forBank = saved.computeIfAbsent(a.getBank(), k -> new ArrayList<>());
            if (!forBank.contains(pwd)) forBank.add(pwd);
        }
        return new UnlockProfile(user == null ? null : user.getDisplayName(), user == null ? null : user.getBirthDate(), saved);
    }
}
