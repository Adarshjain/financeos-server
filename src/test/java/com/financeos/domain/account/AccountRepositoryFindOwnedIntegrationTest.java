package com.financeos.domain.account;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AccountRepository#findByIdAndUser_Id} on real rows: it returns an account only to its owner.
 * No UserContext is set, so the Hibernate userFilter is off and cannot mask a missing user predicate.
 */
@SpringBootTest
class AccountRepositoryFindOwnedIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private User me;
    private User someoneElse;
    private Account mine;
    private Account theirs;

    @BeforeEach
    void setUp() {
        me = saveUser("owned-account-me");
        someoneElse = saveUser("owned-account-other");
        mine = saveAccount(me);
        theirs = saveAccount(someoneElse);
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAll(List.of(mine, theirs));
        userRepository.deleteAll(List.of(me, someoneElse));
    }

    @Test
    void myOwnAccountIsFound() {
        assertEquals(mine.getId(), accountRepository.findByIdAndUser_Id(mine.getId(), me.getId()).orElseThrow().getId());
    }

    @Test
    void anotherUsersAccountIsNotFound() {
        assertTrue(accountRepository.findByIdAndUser_Id(theirs.getId(), me.getId()).isEmpty());
        assertTrue(accountRepository.findByIdAndUser_Id(mine.getId(), someoneElse.getId()).isEmpty());
    }

    @Test
    void aMissingAccountIsNotFound() {
        assertTrue(accountRepository.findByIdAndUser_Id(UUID.randomUUID(), me.getId()).isEmpty());
    }

    @Test
    void noUserFindsNothing() {
        assertTrue(accountRepository.findByIdAndUser_Id(mine.getId(), null).isEmpty());
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }

    private Account saveAccount(User owner) {
        Account account = new Account();
        account.setName("Broker of " + owner.getDisplayName());
        account.setUser(owner);
        account.setType(AccountType.broker);
        return accountRepository.save(account);
    }
}
