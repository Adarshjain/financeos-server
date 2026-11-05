package com.financeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.transaction.TransactionListQueryBuilder;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSearchCriteria;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code findFiltered} (page query plus restricted balance query) returns, row for row, the balance, bank balance
 * (first-mismatch flag) and total the old whole-ledger {@code buildDataQuery} path computed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SearchBalanceEquivalenceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionListQueryBuilder queryBuilder;
    @PersistenceContext private EntityManager em;

    private ApiTestClient api;
    private UUID userId;
    private int lineCounter;

    @BeforeEach
    void setUp() throws Exception {
        String email = "search-eq-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    // ---- oracle ----

    private record Row(String id, BigDecimal balance, BigDecimal bank) {}

    private static BigDecimal dec(Object o) {
        return o == null ? null : o instanceof BigDecimal d ? d : new BigDecimal(o.toString());
    }

    /** The old path: one whole-ledger windowed query, filtered and paged in the outer select. */
    private List<Row> oracle(TransactionSearchCriteria criteria, Pageable pageable) {
        TransactionListQueryBuilder.QueryResult q = queryBuilder.buildDataQuery(userId, criteria, pageable);
        Query query = em.createNativeQuery(q.sql());
        q.params().forEach(query::setParameter);
        query.setFirstResult((int) pageable.getOffset());
        query.setMaxResults(pageable.getPageSize());
        List<Row> rows = new ArrayList<>();
        for (Object o : query.getResultList()) {
            Object[] a = (Object[]) o;
            rows.add(new Row(a[0].toString(), dec(a[1]), dec(a[2])));
        }
        return rows;
    }

    private static void sameNumber(BigDecimal expected, BigDecimal actual, String what) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, what);
        } else {
            assertEquals(0, expected.compareTo(actual), what + ": " + expected + " vs " + actual);
        }
    }

    /** Asserts every row of the page equals the oracle's; returns the page. */
    private Page<TransactionRepository.TransactionBalanceProjection> assertSame(
            TransactionSearchCriteria criteria, Pageable pageable) {
        List<Row> expected = oracle(criteria, pageable);
        Page<TransactionRepository.TransactionBalanceProjection> actual =
                transactionRepository.findFiltered(userId, criteria, pageable);
        assertEquals(expected.size(), actual.getContent().size(), "page size at " + pageable);
        for (int i = 0; i < expected.size(); i++) {
            var got = actual.getContent().get(i);
            assertEquals(expected.get(i).id(), got.getId().toString(), "row order " + i);
            sameNumber(expected.get(i).balance(), got.getBalance(), "balance row " + i);
            sameNumber(expected.get(i).bank(), got.getBankBalance(), "bankBalance row " + i);
        }
        if (!expected.isEmpty()) {
            assertEquals(transactionRepository.countFiltered(userId, criteria), actual.getTotalElements(), "total");
        }
        return actual;
    }

    private static TransactionSearchCriteria all() {
        return new TransactionSearchCriteria(List.of(), null);
    }

    // ---- data ----

    private UUID bank(String name, String last4) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", last4,
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID txn(UUID account, String date, int amount, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", date);
        body.put("amount", amount);
        body.put("description", description);
        return api.create("/api/v1/transactions", body);
    }

    private UUID statement(UUID account, String periodEnd) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO statements (id, user_id, account_id, source, period_start, period_end) "
                        + "VALUES (?, ?, ?, 'file_upload', DATE '2026-01-01', TO_DATE(?, 'YYYY-MM-DD'))",
                id.toString(), userId.toString(), account.toString(), periodEnd);
        return id;
    }

    private void line(UUID statement, UUID transaction, String balanceAfter) {
        jdbc.update("INSERT INTO statement_transactions (statement_id, transaction_id, line_index, balance_after) "
                        + "VALUES (?, ?, ?, ?)",
                statement.toString(), transaction.toString(), lineCounter++, new BigDecimal(balanceAfter));
    }

    /** Two accounts, 12 interleaved transactions; account A has a statement-backed history with a mismatch mid-way. */
    private UUID[] seedStatementBacked() throws Exception {
        UUID a = bank("Eq A", "5001");
        UUID b = bank("Eq B", "5002");
        UUID stA = statement(a, "2026-02-28");
        UUID stB = statement(b, "2026-02-28");
        BigDecimal runA = new BigDecimal("1000");
        BigDecimal runB = new BigDecimal("1000");
        for (int d = 1; d <= 6; d++) {
            String date = "2026-01-0" + d;
            int amtA = -(10 * d);
            runA = runA.add(BigDecimal.valueOf(amtA));
            UUID ta = txn(a, date, amtA, "a" + d);
            // a 500 debit the ledger lacks from day 3 to day 4, recovering on day 5: two mismatch runs possible
            String printed = (d == 3 || d == 4) ? runA.subtract(new BigDecimal("500")).toString() : runA.toString();
            line(stA, ta, printed);
            int amtB = d % 2 == 0 ? 7 * d : -(5 * d);
            runB = runB.add(BigDecimal.valueOf(amtB));
            UUID tb = txn(b, date, amtB, "b" + d);
            line(stB, tb, d == 5 ? runB.add(BigDecimal.ONE).toString() : runB.toString());
        }
        return new UUID[] { a, b };
    }

    // ---- tests ----

    @Test
    void amountAscAndDateAscSortsAcrossPagesEqualTheOldPath() throws Exception {
        seedStatementBacked();
        for (Sort sort : List.of(Sort.by(Sort.Direction.ASC, "amount"), Sort.by(Sort.Direction.ASC, "date"),
                Sort.by(Sort.Direction.DESC, "amount"))) {
            for (int page = 0; page < 4; page++) {
                assertSame(all(), PageRequest.of(page, 4, sort));
            }
        }
    }

    @Test
    void statementBackedRowsWithAMismatchInTheMiddleKeepTheirBankBalance() throws Exception {
        seedStatementBacked();
        boolean sawFlag = false;
        for (int page = 0; page < 12; page++) { // one row per page: each page's date bound differs
            var result = assertSame(all(), PageRequest.of(page, 1));
            for (var p : result.getContent()) {
                sawFlag |= p.getBankBalance() != null;
            }
        }
        assertTrue(sawFlag, "the seeded mismatches must flag at least one row");
        assertSame(all(), PageRequest.of(0, 50));
    }

    @Test
    void aPageOverTheNineHundredChunkBoundaryEqualsTheOldPath() throws Exception {
        UUID a = bank("Chunk A", "6001");
        UUID b = bank("Chunk B", "6002");
        UUID st = statement(a, "2026-12-31");
        for (int i = 0; i < 905; i++) {
            UUID acc = i % 5 == 0 ? b : a;
            UUID t = txn(acc, java.time.LocalDate.of(2026, 1, 1).plusDays(i % 300).toString(), (i % 7) - 3 == 0 ? 11 : -(i % 13 + 1), "n" + i);
            if (i % 50 == 0 && acc.equals(a)) {
                line(st, t, "0"); // printed balance far off: bank flags in the middle of the history
            }
        }
        var page = assertSame(all(), PageRequest.of(0, 1000));
        assertEquals(905, page.getContent().size());
        assertEquals(905, page.getTotalElements());
    }

    @Test
    void anExactlyFullFirstPageTakesTheCountPath() throws Exception {
        seedStatementBacked();
        var full = assertSame(all(), PageRequest.of(0, 12)); // 12 rows, page size 12: count decides the total
        assertEquals(12, full.getTotalElements());
        var shortPage = assertSame(all(), PageRequest.of(0, 13));
        assertEquals(12, shortPage.getTotalElements());
        var second = assertSame(all(), PageRequest.of(1, 6));
        assertEquals(12, second.getTotalElements());
    }

    @Test
    void aMultiAccountPageWithAFilterHidingSomeRowsOfOneAccountEqualsTheOldPath() throws Exception {
        UUID[] ids = seedStatementBacked();
        // credits are hidden by the filter but still move the running balance of their account
        TransactionSearchCriteria debits = new TransactionSearchCriteria(
                List.of(new FilterClause("type", "is", TextNode.valueOf("DEBIT"))), null);
        for (int page = 0; page < 4; page++) {
            assertSame(debits, PageRequest.of(page, 3));
        }
        TransactionSearchCriteria onlyA = new TransactionSearchCriteria(
                List.of(new FilterClause("accountId", "in", mapper.valueToTree(List.of(ids[0].toString()))),
                        new FilterClause("type", "is", TextNode.valueOf("DEBIT"))), null);
        assertSame(onlyA, PageRequest.of(0, 50));
        TransactionSearchCriteria search = new TransactionSearchCriteria(List.of(), "b");
        assertSame(search, PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "amount")));
    }
}
