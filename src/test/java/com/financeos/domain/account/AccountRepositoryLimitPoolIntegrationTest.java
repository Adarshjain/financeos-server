package com.financeos.domain.account;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared-limit queries on real rows: a pool is found only by its owner, members are the
 * owner's accounts in that pool, and pruning deletes only the owner's pools nobody draws on. A new
 * pool is persisted by saving the account that references it. No UserContext is set, so the
 * Hibernate userFilter is off and cannot mask a missing user predicate.
 */
@SpringBootTest
class AccountRepositoryLimitPoolIntegrationTest {

    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate tx;

    private User me;
    private User someoneElse;
    private final List<Account> accounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        me = saveUser("pool-me");
        someoneElse = saveUser("pool-other");
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAll(accounts);
        tx.executeWithoutResult(s -> {
            accountRepository.deleteEmptyLimitPools(me.getId());
            accountRepository.deleteEmptyLimitPools(someoneElse.getId());
        });
        userRepository.deleteAll(List.of(me, someoneElse));
    }

    @Test
    void savingAnAccountPersistsItsNewPool() {
        Account card = saveCard(me, new CreditLimitPool(me, "HDFC", new BigDecimal("500000")));

        CreditLimitPool saved = poolOf(card);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertEquals(saved.getId(), accountRepository.findOwnedLimitPool(saved.getId(), me.getId()).orElseThrow().getId());
    }

    @Test
    void aPoolIsFoundOnlyByItsOwner() {
        UUID pool = poolOf(saveCard(me, new CreditLimitPool(me, "HDFC", new BigDecimal("500000")))).getId();

        assertTrue(accountRepository.findOwnedLimitPool(pool, someoneElse.getId()).isEmpty());
        assertTrue(accountRepository.findOwnedLimitPool(UUID.randomUUID(), me.getId()).isEmpty());
        assertTrue(accountRepository.findOwnedLimitPool(pool, null).isEmpty());
    }

    @Test
    void membersAreTheOwnersAccountsInThatPoolOnly() {
        Account first = saveCard(me, new CreditLimitPool(me, "HDFC", new BigDecimal("500000")));
        CreditLimitPool hdfc = poolOf(first);
        Account second = saveCard(me, hdfc);
        saveCard(me, new CreditLimitPool(me, "ICICI", new BigDecimal("300000")));
        saveCard(me, null);

        Set<UUID> members = accountRepository.findLimitPoolMembers(hdfc.getId(), me.getId()).stream()
                .map(Account::getId).collect(Collectors.toSet());

        assertEquals(Set.of(first.getId(), second.getId()), members);
        assertTrue(accountRepository.findLimitPoolMembers(hdfc.getId(), someoneElse.getId()).isEmpty());
    }

    @Test
    void pruningDeletesOnlyTheOwnersPoolsThatNoAccountUses() {
        Account inUse = saveCard(me, new CreditLimitPool(me, "Kept", new BigDecimal("100000")));
        Account leaving = saveCard(me, new CreditLimitPool(me, "Emptied", new BigDecimal("100000")));
        Account theirs = saveCard(someoneElse, new CreditLimitPool(someoneElse, "Theirs", new BigDecimal("100000")));
        UUID kept = poolOf(inUse).getId();
        UUID emptied = poolOf(leaving).getId();
        UUID theirPool = poolOf(theirs).getId();
        // Their pool loses its account too, but pruning for me must not touch it.
        unpool(leaving);
        unpool(theirs);

        int deleted = tx.execute(s -> accountRepository.deleteEmptyLimitPools(me.getId()));

        assertEquals(1, deleted);
        assertTrue(accountRepository.findOwnedLimitPool(kept, me.getId()).isPresent());
        assertTrue(accountRepository.findOwnedLimitPool(emptied, me.getId()).isEmpty());
        assertTrue(accountRepository.findOwnedLimitPool(theirPool, someoneElse.getId()).isPresent());
    }

    @Test
    void aPooledCardMayHaveNoLimitOfItsOwn() {
        Account card = saveCard(me, new CreditLimitPool(me, "HDFC", new BigDecimal("500000")));

        assertNull(tx.execute(s -> accountRepository.findById(card.getId()).orElseThrow().getCreditCardDetails().getCreditLimit()));
    }

    // ------------------------------------------------------------------ fixtures

    private CreditLimitPool poolOf(Account card) {
        return tx.execute(s -> {
            CreditLimitPool p = accountRepository.findById(card.getId()).orElseThrow().getCreditCardDetails().getLimitPool();
            p.getName();
            return p;
        });
    }

    private void unpool(Account card) {
        tx.executeWithoutResult(s -> {
            Account managed = accountRepository.findById(card.getId()).orElseThrow();
            managed.getCreditCardDetails().setLimitPool(null);
            managed.getCreditCardDetails().setCreditLimit(new BigDecimal("1000"));
        });
    }

    private Account saveCard(User owner, CreditLimitPool pool) {
        Account saved = tx.execute(s -> {
            Account account = new Account("Card " + UUID.randomUUID(), AccountType.credit_card);
            account.setUser(owner);
            AccountCreditCardDetails details = new AccountCreditCardDetails(account, pool == null ? new BigDecimal("1000") : null, null);
            details.setUser(owner);
            details.setLimitPool(pool == null || pool.getId() == null ? pool : entityManager.merge(pool));
            account.setCreditCardDetails(details);
            return accountRepository.save(account);
        });
        accounts.add(saved);
        return saved;
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }
}
