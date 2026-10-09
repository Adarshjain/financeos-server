package com.financeos.domain.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The row-breakdown queries {@link TransactionRepository#findBalanceTransactions} and
 * {@link TransactionRepository#findBalanceMovements} select exactly the rows the account balance
 * ({@link TransactionRepository#findBalanceAggregatesByAccountId}) sums: every transaction of the
 * account, excluded ones too, dated strictly after the anchor (all of them without one), with
 * CREDIT counted as a credit and every other type as a debit — newest first, ties by created time
 * then id.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionBalanceQueriesIntegrationTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 6, 30);
    private static final Instant T0 = Instant.parse("2026-07-10T10:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private UUID userId;
    private Account account;
    private Account otherAccount;

    @BeforeEach
    void setUp() throws Exception {
        String email = "balance-queries-" + UUID.randomUUID() + "@example.test";
        ApiTestClient api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        account = accountRepository.findById(api.create("/api/v1/accounts", Map.of("type", "bank_account",
                "name", "Queries Bank", "last4", "1234", "openingBalance", 100, "financialPosition", "asset",
                "excludeFromNetAsset", false))).orElseThrow();
        otherAccount = accountRepository.findById(api.create("/api/v1/accounts", Map.of("type", "generic",
                "name", "Other Wallet", "financialPosition", "asset", "excludeFromNetAsset", false))).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void anchoredListingIsExactlyTheTransactionsAfterTheAnchorIncludingExcludedNewestFirst() {
        txn(account, ANCHOR.minusDays(3), "500", TransactionType.CREDIT, false, T0);
        txn(account, ANCHOR, "70", TransactionType.DEBIT, false, T0);
        UUID older = txn(account, ANCHOR.plusDays(1), "40", TransactionType.DEBIT, true, T0);
        UUID earlierCreated = txn(account, ANCHOR.plusDays(5), "300", TransactionType.CREDIT, false, T0);
        UUID laterCreated = txn(account, ANCHOR.plusDays(5), "25", TransactionType.DEBIT, false, T0.plusSeconds(60));
        UUID tieA = txn(account, ANCHOR.plusDays(3), "10", TransactionType.DEBIT, false, T0);
        UUID tieB = txn(account, ANCHOR.plusDays(3), "15", TransactionType.CREDIT, false, T0);
        txn(otherAccount, ANCHOR.plusDays(4), "999", TransactionType.CREDIT, false, T0);

        Page<Transaction> page = transactionRepository.findBalanceTransactions(account.getId(), ANCHOR, PageRequest.of(0, 50));

        List<UUID> ties = new ArrayList<>(List.of(tieA, tieB));
        ties.sort(Comparator.comparing(UUID::toString).reversed());
        assertEquals(List.of(laterCreated, earlierCreated, ties.get(0), ties.get(1), older), ids(page));
        assertEquals(5, page.getTotalElements());
    }

    @Test
    void anchoredMovementsSumToTheBalancesPostAnchorSum() {
        txn(account, ANCHOR.minusDays(3), "500", TransactionType.CREDIT, false, T0);
        txn(account, ANCHOR, "70", TransactionType.DEBIT, false, T0);
        txn(account, ANCHOR.plusDays(1), "40", TransactionType.DEBIT, true, T0);
        txn(account, ANCHOR.plusDays(2), "300", TransactionType.CREDIT, true, T0);
        txn(account, ANCHOR.plusDays(3), "25.50", TransactionType.DEBIT, false, T0);
        txn(otherAccount, ANCHOR.plusDays(4), "999", TransactionType.CREDIT, false, T0);

        TransactionRepository.BalanceMovementsProjection movements =
                transactionRepository.findBalanceMovements(account.getId(), ANCHOR);
        TransactionRepository.BalanceAggregatesProjection aggregates =
                transactionRepository.findBalanceAggregatesByAccountId(account.getId(), ANCHOR);

        assertEquals(1L, movements.getCreditCount());
        assertDecimal("300", movements.getCreditSum());
        assertEquals(2L, movements.getDebitCount());
        assertDecimal("65.50", movements.getDebitSum());
        assertEquals(2L, movements.getExcludedCount());
        assertDecimal(aggregates.getPostAnchorSum(), movements.getCreditSum().subtract(movements.getDebitSum()));
    }

    @Test
    void withoutAnAnchorEveryTransactionIsListedAndSumsToTheTotal() {
        UUID first = txn(account, ANCHOR.minusDays(3), "500", TransactionType.CREDIT, false, T0);
        UUID second = txn(account, ANCHOR, "70", TransactionType.DEBIT, true, T0);
        UUID third = txn(account, ANCHOR.plusDays(1), "40", TransactionType.DEBIT, false, T0);
        txn(otherAccount, ANCHOR.plusDays(4), "999", TransactionType.CREDIT, false, T0);

        Page<Transaction> page = transactionRepository.findBalanceTransactions(account.getId(), null, PageRequest.of(0, 50));
        TransactionRepository.BalanceMovementsProjection movements =
                transactionRepository.findBalanceMovements(account.getId(), null);

        assertEquals(List.of(third, second, first), ids(page));
        assertEquals(1L, movements.getCreditCount());
        assertEquals(2L, movements.getDebitCount());
        assertEquals(1L, movements.getExcludedCount());
        assertDecimal(transactionRepository.findTotalTransactionSumByAccountId(account.getId()),
                movements.getCreditSum().subtract(movements.getDebitSum()));
        assertDecimal(transactionRepository.findBalanceAggregatesByAccountId(account.getId(), ANCHOR.minusYears(10)).getTotalSum(),
                movements.getCreditSum().subtract(movements.getDebitSum()));
    }

    @Test
    void listingPagesWithTheCountOfEveryMatchingRow() {
        UUID a = txn(account, ANCHOR.plusDays(1), "1", TransactionType.DEBIT, false, T0);
        UUID b = txn(account, ANCHOR.plusDays(2), "2", TransactionType.DEBIT, false, T0);
        UUID c = txn(account, ANCHOR.plusDays(3), "3", TransactionType.DEBIT, false, T0);
        txn(account, ANCHOR, "4", TransactionType.DEBIT, false, T0);

        Page<Transaction> first = transactionRepository.findBalanceTransactions(account.getId(), ANCHOR, PageRequest.of(0, 2));
        Page<Transaction> second = transactionRepository.findBalanceTransactions(account.getId(), ANCHOR, PageRequest.of(1, 2));

        assertEquals(List.of(c, b), ids(first));
        assertEquals(List.of(a), ids(second));
        assertEquals(3, second.getTotalElements());
        assertEquals(2, second.getTotalPages());
    }

    @Test
    void anAccountWithoutTransactionsHasZeroMovementsAndAnEmptyListing() {
        TransactionRepository.BalanceMovementsProjection movements =
                transactionRepository.findBalanceMovements(account.getId(), ANCHOR);
        Page<Transaction> page = transactionRepository.findBalanceTransactions(account.getId(), null, PageRequest.of(0, 10));

        assertEquals(0L, movements.getCreditCount());
        assertDecimal("0", movements.getCreditSum());
        assertEquals(0L, movements.getDebitCount());
        assertDecimal("0", movements.getDebitSum());
        assertEquals(0L, movements.getExcludedCount());
        assertEquals(0, page.getTotalElements());
    }

    private UUID txn(Account owner, LocalDate date, String amount, TransactionType type, boolean excluded, Instant createdAt) {
        Transaction t = new Transaction(owner, date, new BigDecimal(amount), "t", TransactionSource.manual, type, false, excluded);
        t.setUser(owner.getUser());
        t.setCreatedAt(createdAt);
        return transactionRepository.save(t).getId();
    }

    private static List<UUID> ids(Page<Transaction> page) {
        return page.getContent().stream().map(Transaction::getId).toList();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " vs " + actual);
    }

    private static void assertDecimal(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), expected + " vs " + actual);
    }
}
