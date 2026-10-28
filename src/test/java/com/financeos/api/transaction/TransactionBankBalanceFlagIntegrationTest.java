package com.financeos.api.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The list's {@code bankBalance}: on the first row (a day's last statement line) where our running balance stops
 * matching the bank's printed end-of-day balance, null everywhere else. Real persistence; statement lines are
 * inserted directly.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionBankBalanceFlagIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private UUID userId;
    private UUID bank;
    private UUID statementId;
    private int lineCounter;

    @BeforeEach
    void setUp() throws Exception {
        String email = "bank-balance-flag-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Flag Bank", "last4", "4321",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        statementId = newStatement(bank, "2026-01-31");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void allDaysMatchFlagsNothing() throws Exception {
        UUID a = txn(bank, "2026-01-05", -100, "d1");
        UUID b = txn(bank, "2026-01-06", -50, "d2");
        line(statementId, a, "900");
        line(statementId, b, "850");
        assertEquals(Map.of(), flags(bank));
    }

    @Test
    void missingEntryFlagsOnlyTheFirstMismatchingDay() throws Exception {
        UUID a = txn(bank, "2026-01-05", -100, "d1");
        UUID b = txn(bank, "2026-01-06", -50, "d2");
        UUID c = txn(bank, "2026-01-07", -10, "d3");
        line(statementId, a, "900");
        line(statementId, b, "650"); // a 200 debit the ledger lacks
        line(statementId, c, "640");
        assertEquals(Map.of("d2", "650"), flags(bank));
    }

    @Test
    void aRunThatRecoversAndBreaksAgainFlagsTwice() throws Exception {
        UUID a = txn(bank, "2026-01-05", -100, "d1");
        UUID b = txn(bank, "2026-01-06", -50, "d2");
        UUID c = txn(bank, "2026-01-07", -10, "d3");
        UUID d = txn(bank, "2026-01-08", -10, "d4");
        UUID e = txn(bank, "2026-01-09", -10, "d5");
        line(statementId, a, "900");
        line(statementId, b, "700");
        line(statementId, c, "840");
        line(statementId, d, "830");
        line(statementId, e, "800");
        assertEquals(Map.of("d2", "700", "d5", "800"), flags(bank));
    }

    @Test
    void sameDayOrderDifferencesThatNetOutDoNotFlag() throws Exception {
        UUID debit = txn(bank, "2026-01-05", -100, "d1-debit");
        UUID credit = txn(bank, "2026-01-05", 50, "d1-credit");
        // The bank listed the credit first, then the debit; our order is the reverse. Day ends agree at 950.
        line(statementId, credit, "1050");
        line(statementId, debit, "950");
        assertEquals(Map.of(), flags(bank));
    }

    @Test
    void rowsWithoutLinesAreNeverFlaggedAndDoNotBreakTheRun() throws Exception {
        UUID a = txn(bank, "2026-01-05", -100, "d1");
        UUID b = txn(bank, "2026-01-06", -50, "d2");
        txn(bank, "2026-01-07", -10, "manual-only"); // no statement line
        UUID d = txn(bank, "2026-01-08", -10, "d4");
        line(statementId, a, "900");
        line(statementId, b, "700");
        line(statementId, d, "670");
        // d2 starts the run; the line-less day neither resets nor starts one; d4 stays inside the run.
        assertEquals(Map.of("d2", "700"), flags(bank));
    }

    @Test
    void theMostRecentStatementsLineWins() throws Exception {
        UUID a = txn(bank, "2026-01-05", -100, "d1");
        line(statementId, a, "123"); // stale line from an older statement
        UUID later = newStatement(bank, "2026-02-28");
        line(later, a, "900");
        assertEquals(Map.of(), flags(bank));
    }

    @Test
    void aFilteredPagedListKeepsTheFlagOnItsAnchorRow() throws Exception {
        UUID a = txn(bank, "2026-01-05", -100, "d1");
        UUID b = txn(bank, "2026-01-06", -50, "d2-anchor");
        UUID c = txn(bank, "2026-01-07", -10, "d3");
        line(statementId, a, "900");
        line(statementId, b, "650");
        line(statementId, c, "640");
        JsonNode page = api.postJson("/api/v1/transactions/search?size=1&page=0",
                Map.of("filters", List.of(), "search", "d2-anchor"), 200);
        assertEquals(1, page.get("content").size());
        JsonNode row = page.get("content").get(0);
        assertEquals(0, new java.math.BigDecimal("650").compareTo(row.get("bankBalance").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("850").compareTo(row.get("balance").decimalValue()));
        // Paging without the filter: the unflagged rows still carry null.
        JsonNode first = api.getJson("/api/v1/transactions?size=1&page=0", 200).get("content").get(0);
        assertEquals("d3", first.get("description").asText());
        assertEquals(true, first.get("bankBalance") == null || first.get("bankBalance").isNull());
    }

    @Test
    void creditCardPrintedBalancesAreOwedAmountsAgainstANegativeLedger() throws Exception {
        UUID card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Flag Card",
                "last4", "8421", "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        UUID stmt = newStatement(card, "2026-01-31");
        UUID a = txn(card, "2026-01-05", -300, "c1");
        UUID b = txn(card, "2026-01-06", -200, "c2");
        UUID c = txn(card, "2026-01-07", -100, "c3");
        line(stmt, a, "300");   // owed 300 = ledger -300
        line(stmt, b, "700");   // bank says 700 owed, ledger -500
        line(stmt, c, "800");
        assertEquals(Map.of("c2", "-700"), flags(card));
    }

    @Test
    void aMismatchOnOneAccountDoesNotLeakIntoAnotherAccountOfTheSameUser() throws Exception {
        UUID other = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Other Bank", "last4", "9876",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID otherStmt = newStatement(other, "2026-01-31");
        UUID a1 = txn(bank, "2026-01-05", -100, "a-d1");
        UUID a2 = txn(bank, "2026-01-06", -50, "a-d2");
        UUID b1 = txn(other, "2026-01-05", -100, "b-d1");
        UUID b2 = txn(other, "2026-01-06", -50, "b-d2");
        line(statementId, a1, "900");
        line(statementId, a2, "650"); // mismatch on A only
        line(otherStmt, b1, "900");
        line(otherStmt, b2, "850");
        assertEquals(Map.of("a-d2", "650"), flags(bank));
        assertEquals(Map.of(), flags(other));
    }

    @Test
    void aDayCoveredByTwoStatementsFlagsTheNewerStatementsAnchorNotTheLedgerLastRow() throws Exception {
        UUID first = txn(bank, "2026-01-05", -100, "first-in-ledger");
        UUID last = txn(bank, "2026-01-05", -50, "last-in-ledger");
        line(statementId, last, "850");        // older statement: matches the day end (1000-150)
        UUID later = newStatement(bank, "2026-02-28");
        line(later, first, "777");             // newer statement's last line that day: mismatch
        assertEquals(Map.of("first-in-ledger", "777"), flags(bank));
    }

    @Test
    void aTransactionWhoseNewestLineHasNoBalanceIsNotComparedAgainstAStaleOlderValue() throws Exception {
        UUID a = txn(bank, "2026-01-05", -100, "d1");
        line(statementId, a, "123");           // stale older value that would mismatch
        UUID later = newStatement(bank, "2026-02-28");
        jdbc.update("INSERT INTO statement_transactions (statement_id, transaction_id, line_index, balance_after) "
                        + "VALUES (?, ?, ?, NULL)",
                later.toString(), a.toString(), lineCounter++);
        assertEquals(Map.of(), flags(bank));
    }

    // ---- helpers ----

    /** description -> bankBalance of every flagged row of the account (all pages of the plain list). */
    private Map<String, String> flags(UUID account) throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (JsonNode row : api.getJson("/api/v1/transactions?size=100", 200).get("content")) {
            if (!row.get("accountId").asText().equals(account.toString())) {
                continue;
            }
            JsonNode bb = row.get("bankBalance");
            if (bb != null && !bb.isNull()) {
                out.put(row.get("description").asText(), bb.decimalValue().stripTrailingZeros().toPlainString());
            }
        }
        return out;
    }

    private UUID txn(UUID account, String date, int amount, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", date);
        body.put("amount", amount);
        body.put("description", description);
        return api.create("/api/v1/transactions", body);
    }

    private UUID newStatement(UUID account, String periodEnd) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO statements (id, user_id, account_id, source, period_start, period_end) "
                        + "VALUES (?, ?, ?, 'file_upload', DATE '2026-01-01', TO_DATE(?, 'YYYY-MM-DD'))",
                id.toString(), userId.toString(), account.toString(), periodEnd);
        return id;
    }

    private void line(UUID statement, UUID transaction, String balanceAfter) {
        jdbc.update("INSERT INTO statement_transactions (statement_id, transaction_id, line_index, balance_after) "
                        + "VALUES (?, ?, ?, ?)",
                statement.toString(), transaction.toString(), lineCounter++, new java.math.BigDecimal(balanceAfter));
    }
}
