package com.financeos.domain.transaction;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The TransactionRepository queries added for the navigation redesign, on real rows: the Inbox
 * review counts ({@code countByUserIdAndReviewType}, {@code countByUserIdAndReviewTypeAndReason})
 * and the card unbilled-spend sums ({@code sumIncludedDebitsAfter}, {@code sumIncludedDebits}).
 * No UserContext is set, so the Hibernate userFilter cannot mask a missing user predicate.
 */
@SpringBootTest
class TransactionRepositoryNavigationQueriesIntegrationTest {

    private static final LocalDate PERIOD_END = LocalDate.of(2026, 9, 15);

    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private User user;
    private User otherUser;
    private Account card;
    private Account otherCard;
    private Account otherUsersCard;
    private final List<UUID> txnIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        user = saveUser("txn-nav");
        otherUser = saveUser("txn-nav-other");
        card = saveCard(user, "Card A");
        otherCard = saveCard(user, "Card B");
        otherUsersCard = saveCard(otherUser, "Other user's card");
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAllById(txnIds);
        accountRepository.deleteAll(List.of(card, otherCard, otherUsersCard));
        userRepository.deleteAll(List.of(user, otherUser));
    }

    // ------------------------------------------------------------------ countByUserIdAndReviewType

    @Test
    void reviewCount_countsOnlyTheUsersNeedsReviewRows() {
        review(card, ReviewType.NEEDS_REVIEW, Set.of(ReviewReason.UNRECONCILED));
        review(card, ReviewType.NEEDS_REVIEW, Set.of(ReviewReason.CATEGORY_UNVERIFIED, ReviewReason.DUPLICATE_SUSPECT));
        review(otherCard, ReviewType.NEEDS_REVIEW, Set.of());
        review(card, ReviewType.AUTO_REVIEWED, Set.of());
        review(card, ReviewType.MANUALLY_REVIEWED, Set.of());
        review(card, ReviewType.NA, Set.of());
        review(card, null, Set.of());
        review(otherUsersCard, ReviewType.NEEDS_REVIEW, Set.of(ReviewReason.UNRECONCILED));

        assertEquals(3, transactionRepository.countByUserIdAndReviewType(user.getId(), ReviewType.NEEDS_REVIEW));
        assertEquals(1, transactionRepository.countByUserIdAndReviewType(otherUser.getId(), ReviewType.NEEDS_REVIEW));
    }

    @Test
    void reviewCount_isZeroWhenNothingNeedsReview() {
        review(card, ReviewType.AUTO_REVIEWED, Set.of());

        assertEquals(0, transactionRepository.countByUserIdAndReviewType(user.getId(), ReviewType.NEEDS_REVIEW));
    }

    // ------------------------------------------------------------------ countByUserIdAndReviewTypeAndReason

    @Test
    void reasonCount_countsATransactionWithSeveralReasonsOnce() {
        review(card, ReviewType.NEEDS_REVIEW,
                Set.of(ReviewReason.CATEGORY_UNVERIFIED, ReviewReason.UNRECONCILED, ReviewReason.DUPLICATE_SUSPECT));
        review(card, ReviewType.NEEDS_REVIEW, Set.of(ReviewReason.CATEGORY_UNVERIFIED));

        assertEquals(2, reasonCount(user, ReviewReason.CATEGORY_UNVERIFIED));
        assertEquals(1, reasonCount(user, ReviewReason.UNRECONCILED));
    }

    @Test
    void reasonCount_skipsRowsWithoutTheReason_notNeedingReview_orOfAnotherUser() {
        review(card, ReviewType.NEEDS_REVIEW, Set.of(ReviewReason.UNRECONCILED));
        review(card, ReviewType.NEEDS_REVIEW, Set.of());
        review(card, ReviewType.AUTO_REVIEWED, Set.of(ReviewReason.CATEGORY_UNVERIFIED));
        review(otherUsersCard, ReviewType.NEEDS_REVIEW, Set.of(ReviewReason.CATEGORY_UNVERIFIED));

        assertEquals(0, reasonCount(user, ReviewReason.CATEGORY_UNVERIFIED));
        assertEquals(1, reasonCount(otherUser, ReviewReason.CATEGORY_UNVERIFIED));
    }

    // ------------------------------------------------------------------ sumIncludedDebitsAfter

    @Test
    void debitsAfter_sumsIncludedDebitsStrictlyAfterTheDate() {
        money(card, PERIOD_END.minusDays(1), "1000", TransactionType.DEBIT, false);
        money(card, PERIOD_END, "2000", TransactionType.DEBIT, false);              // on the date: billed
        money(card, PERIOD_END.plusDays(1), "300.50", TransactionType.DEBIT, false);
        money(card, PERIOD_END.plusDays(10), "200", TransactionType.DEBIT, false);
        money(card, PERIOD_END.plusDays(2), "5000", TransactionType.CREDIT, false); // payment, not spend
        money(card, PERIOD_END.plusDays(3), "4000", TransactionType.DEBIT, true);   // excluded
        money(otherCard, PERIOD_END.plusDays(1), "7000", TransactionType.DEBIT, false);

        assertAmount("500.50", transactionRepository.sumIncludedDebitsAfter(card.getId(), PERIOD_END));
    }

    @Test
    void debitsAfter_isZeroWhenNothingQualifies() {
        money(card, PERIOD_END, "2000", TransactionType.DEBIT, false);
        money(card, PERIOD_END.plusDays(1), "100", TransactionType.CREDIT, false);

        assertAmount("0", transactionRepository.sumIncludedDebitsAfter(card.getId(), PERIOD_END));
    }

    // ------------------------------------------------------------------ sumIncludedDebits

    @Test
    void allDebits_sumsEveryIncludedDebitOfTheAccountOnly() {
        money(card, PERIOD_END.minusDays(40), "1000", TransactionType.DEBIT, false);
        money(card, PERIOD_END.plusDays(1), "250.25", TransactionType.DEBIT, false);
        money(card, PERIOD_END, "900", TransactionType.CREDIT, false);
        money(card, PERIOD_END, "4000", TransactionType.DEBIT, true);
        money(otherCard, PERIOD_END, "7000", TransactionType.DEBIT, false);

        assertAmount("1250.25", transactionRepository.sumIncludedDebits(card.getId()));
    }

    @Test
    void allDebits_isZeroForAnAccountWithoutTransactions() {
        assertAmount("0", transactionRepository.sumIncludedDebits(card.getId()));
    }

    // ------------------------------------------------------------------ helpers

    private long reasonCount(User owner, ReviewReason reason) {
        return transactionRepository.countByUserIdAndReviewTypeAndReason(owner.getId(), ReviewType.NEEDS_REVIEW, reason);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private void review(Account account, ReviewType reviewType, Set<ReviewReason> reasons) {
        Transaction t = base(account, PERIOD_END, "10", TransactionType.DEBIT, false);
        t.setReviewType(reviewType);
        t.setReviewReasons(new java.util.HashSet<>(reasons));
        txnIds.add(transactionRepository.save(t).getId());
    }

    private void money(Account account, LocalDate date, String amount, TransactionType type, boolean excluded) {
        txnIds.add(transactionRepository.save(base(account, date, amount, type, excluded)).getId());
    }

    private static Transaction base(Account account, LocalDate date, String amount, TransactionType type, boolean excluded) {
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "txn", TransactionSource.manual, type,
                false, excluded);
        t.setUser(account.getUser());
        return t;
    }

    private User saveUser(String prefix) {
        User u = new User();
        u.setDisplayName(prefix);
        u.setEmail(prefix + "-" + UUID.randomUUID() + "@example.test");
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }

    private Account saveCard(User owner, String name) {
        Account a = new Account();
        a.setUser(owner);
        a.setName(name);
        a.setType(AccountType.credit_card);
        return accountRepository.save(a);
    }
}
