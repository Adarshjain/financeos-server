package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.*;

import com.financeos.api.transaction.dto.BatchReviewResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@SpringBootTest
public class TransactionServiceBatchIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private User userA;
    private User userB;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        // Create user A
        userA = new User();
        userA.setDisplayName("User A");
        userA.setEmail("usera@test.com");
        userA.setPasswordHash("hash");
        userA = userRepository.save(userA);

        // Create user B
        userB = new User();
        userB.setDisplayName("User B");
        userB.setEmail("userb@test.com");
        userB.setPasswordHash("hash");
        userB = userRepository.save(userB);

        // Create account A
        accountA = new Account();
        accountA.setName("Account A");
        accountA.setUser(userA);
        accountA.setType(AccountType.bank_account);
        accountA = accountRepository.save(accountA);

        // Create account B
        accountB = new Account();
        accountB.setName("Account B");
        accountB.setUser(userB);
        accountB.setType(AccountType.bank_account);
        accountB = accountRepository.save(accountB);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testBatchReview_acrossUsers_scopingEnforced() {
        // Create transaction A1 (NEEDS_REVIEW) owned by user A
        Transaction txnA1 = new Transaction();
        txnA1.setUser(userA);
        txnA1.setAccount(accountA);
        txnA1.setAmount(BigDecimal.valueOf(100.00));
        txnA1.setDate(LocalDate.parse("2026-07-11"));
        txnA1.setReviewType(ReviewType.NEEDS_REVIEW);
        txnA1.setSource(TransactionSource.manual);
        txnA1.setType(TransactionType.DEBIT);
        txnA1.setReviewReasons(new HashSet<>(List.of(ReviewReason.UNRECONCILED)));
        txnA1 = transactionRepository.save(txnA1);

        // Create transaction B1 (NEEDS_REVIEW) owned by user B
        Transaction txnB1 = new Transaction();
        txnB1.setUser(userB);
        txnB1.setAccount(accountB);
        txnB1.setAmount(BigDecimal.valueOf(200.00));
        txnB1.setDate(LocalDate.parse("2026-07-11"));
        txnB1.setReviewType(ReviewType.NEEDS_REVIEW);
        txnB1.setSource(TransactionSource.manual);
        txnB1.setType(TransactionType.DEBIT);
        txnB1.setReviewReasons(new HashSet<>(List.of(ReviewReason.UNRECONCILED)));
        txnB1 = transactionRepository.save(txnB1);

        // Set UserContext to user A
        UserContext.setCurrentUserId(userA.getId());

        // Attempt batch review of both transaction IDs (A1 and B1)
        List<UUID> idsToReview = List.of(txnA1.getId(), txnB1.getId());
        List<ReviewReason> reasons = List.of(ReviewReason.UNRECONCILED);

        BatchReviewResponse response = transactionService.batchReview(idsToReview, ReviewType.MANUALLY_REVIEWED, reasons);

        // Verify that A1 succeeded, and B1 failed (with NOT_FOUND due to Hibernate UserFilter scoping, or NOT_OWNED)
        assertTrue(response.succeededIds().contains(txnA1.getId().toString()), "User A's transaction should succeed");
        assertFalse(response.succeededIds().contains(txnB1.getId().toString()), "User B's transaction should not succeed");

        final String txnB1IdStr = txnB1.getId().toString();
        boolean failedAsNotFoundOrNotOwned = response.failures().stream()
                .anyMatch(f -> f.id().equals(txnB1IdStr) && 
                        (f.reason().equals("NOT_FOUND") || f.reason().equals("NOT_OWNED")));
        assertTrue(failedAsNotFoundOrNotOwned, "User B's transaction failure should be NOT_FOUND or NOT_OWNED");

        // Assert txnB1 was not mutated (it should still be NEEDS_REVIEW)
        UserContext.clear();
        Transaction loadedB1 = transactionRepository.findById(txnB1.getId()).orElseThrow();
        assertEquals(ReviewType.NEEDS_REVIEW, loadedB1.getReviewType());
    }
}
