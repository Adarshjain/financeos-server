package com.financeos.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /accounts/{id}/cardholders/stats over real persistence with today pinned to 2026-10-19:
 * financial-year and billing-cycle net spend, last use, per-card counts, and ownership.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CardholderStatsIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CardRepository cardRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private User user;
    private UUID otherUserId;
    private UUID accountId;
    private String accountBase;

    @BeforeEach
    void setUp() throws Exception {
        AppTime.useClock(Clock.fixed(TODAY.atTime(12, 0).atZone(AppTime.zone()).toInstant(), AppTime.zone()));
        String email = "cardholder-stats-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        String otherEmail = "cardholder-stats-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        accountId = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Stats Card",
                "last4", "1111", "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        accountBase = "/api/v1/accounts/" + accountId + "/cardholders";
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), otherUserId));
    }

    @Test
    void financialYearBoundariesAndRefundsAndBillPayments() throws Exception {
        UUID card = primaryCard();
        tx(card, LocalDate.of(2026, 3, 31), "9999", TransactionType.DEBIT); // previous FY
        tx(card, LocalDate.of(2026, 4, 1), "1000", TransactionType.DEBIT);  // first day of FY
        tx(card, LocalDate.of(2026, 6, 10), "300", TransactionType.CREDIT); // refund
        UUID payment = tx(card, LocalDate.of(2026, 7, 5), "5000", TransactionType.CREDIT);
        linkAsBillPayment(payment);

        JsonNode stats = primaryStats();

        assertEquals(0, new BigDecimal("700").compareTo(stats.get("spendThisYear").decimalValue()), "1000 - 300 refund, no bill payment, no March");
        assertEquals("2026-04-01", stats.get("yearFrom").asText());
        assertEquals("2026-10-19", stats.get("yearTo").asText());
        assertEquals(4, stats.get("transactionCount").asInt(), "all transactions count, in or out of the year");
    }

    @Test
    void spendIsFlooredAtZero() throws Exception {
        UUID card = primaryCard();
        tx(card, LocalDate.of(2026, 8, 1), "100", TransactionType.DEBIT);
        tx(card, LocalDate.of(2026, 9, 1), "400", TransactionType.CREDIT);

        JsonNode stats = primaryStats();

        assertEquals(0, BigDecimal.ZERO.compareTo(stats.get("spendThisYear").decimalValue()));
    }

    @Test
    void cycleWindowComesFromStatementsAndFutureTransactionsAreNotInTheYear() throws Exception {
        statement(LocalDate.of(2026, 10, 15));
        UUID card = primaryCard();
        tx(card, LocalDate.of(2026, 10, 15), "111", TransactionType.DEBIT); // previous cycle
        tx(card, LocalDate.of(2026, 10, 16), "200", TransactionType.DEBIT); // current cycle starts
        tx(card, LocalDate.of(2026, 10, 18), "50", TransactionType.CREDIT);
        tx(card, LocalDate.of(2026, 10, 25), "777", TransactionType.DEBIT); // in the cycle, after today

        JsonNode stats = primaryStats();

        assertEquals("2026-10-16", stats.get("cycleFrom").asText());
        assertEquals("2026-11-15", stats.get("cycleTo").asText());
        assertEquals(0, new BigDecimal("927").compareTo(stats.get("currentCycleSpend").decimalValue()), "200 - 50 + 777");
        assertEquals(0, new BigDecimal("261").compareTo(stats.get("spendThisYear").decimalValue()), "year stops at today: 111 + 200 - 50");
    }

    @Test
    void cycleFallsBackToTheCalendarMonthWithoutStatements() throws Exception {
        UUID card = primaryCard();
        tx(card, LocalDate.of(2026, 9, 30), "10", TransactionType.DEBIT);
        tx(card, LocalDate.of(2026, 10, 2), "25", TransactionType.DEBIT);

        JsonNode stats = primaryStats();

        assertEquals("2026-10-01", stats.get("cycleFrom").asText());
        assertEquals("2026-10-31", stats.get("cycleTo").asText());
        assertEquals(0, new BigDecimal("25").compareTo(stats.get("currentCycleSpend").decimalValue()));
    }

    @Test
    void bankAccountUsesTheCalendarMonth() throws Exception {
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Stats Bank", "last4", "2222",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID card = bankDebitCard(bank, "2223");
        saveTx(bank, card, LocalDate.of(2026, 10, 3), "40", TransactionType.DEBIT);

        JsonNode stats = api.getJson("/api/v1/accounts/" + bank + "/cardholders/stats", 200).get(0);

        assertEquals("2026-10-01", stats.get("cycleFrom").asText());
        assertEquals("2026-10-31", stats.get("cycleTo").asText());
        assertEquals(0, new BigDecimal("40").compareTo(stats.get("currentCycleSpend").decimalValue()));
    }

    @Test
    void perCardCountsDatesAndLastUsedAcrossCardsAndClosedHoldersAreIncluded() throws Exception {
        UUID oldCard = primaryCard();
        api.postJson(accountBase + "/" + primaryHolderId() + "/cards/" + oldCard + "/replace",
                Map.of("newLast4", "3333", "issuedOn", "2026-05-01"), 200);
        UUID newCard = UUID.fromString(primaryHolder().get("cards").get(0).get("id").asText());
        tx(oldCard, LocalDate.of(2026, 2, 1), "10", TransactionType.DEBIT);
        tx(oldCard, LocalDate.of(2026, 4, 20), "20", TransactionType.DEBIT);
        tx(newCard, LocalDate.of(2026, 9, 9), "30", TransactionType.DEBIT);

        UUID addon = api.create(accountBase, Map.of("personName", "Asha", "relationship", "SPOUSE", "last4", "4444"));
        UUID addonCard = UUID.fromString(api.getJson(accountBase, 200).get(1).get("cards").get(0).get("id").asText());
        tx(addonCard, LocalDate.of(2026, 7, 7), "5", TransactionType.DEBIT);
        api.postJson(accountBase + "/" + addon + "/close", Map.of("closedOn", "2026-08-01"), 200);

        JsonNode all = api.getJson(accountBase + "/stats", 200);
        JsonNode listed = api.getJson(accountBase, 200);
        assertEquals(2, all.size(), "the closed holder is still reported");

        JsonNode primary = byHolder(all, primaryHolderId());
        assertEquals(3, primary.get("transactionCount").asInt());
        assertEquals("2026-09-09", primary.get("lastUsedOn").asText());
        JsonNode oldStats = byCard(primary, oldCard);
        assertEquals(2, oldStats.get("transactionCount").asInt());
        assertEquals("2026-02-01", oldStats.get("firstUsedOn").asText());
        assertEquals("2026-04-20", oldStats.get("lastUsedOn").asText());
        assertEquals(1, byCard(primary, newCard).get("transactionCount").asInt());

        JsonNode addonStats = byHolder(all, addon);
        assertEquals(1, addonStats.get("transactionCount").asInt());
        assertEquals("2026-07-07", addonStats.get("lastUsedOn").asText());

        for (JsonNode holder : listed) {
            assertEquals(holder.get("transactionCount").asLong(),
                    byHolder(all, UUID.fromString(holder.get("id").asText())).get("transactionCount").asLong(),
                    "same count as the cardholder list");
        }
    }

    @Test
    void cardWithoutTransactionsHasNoDates() throws Exception {
        JsonNode stats = primaryStats();

        assertEquals(0, stats.get("transactionCount").asInt());
        assertTrue(stats.get("lastUsedOn").isNull());
        JsonNode card = stats.get("cards").get(0);
        assertEquals(0, card.get("transactionCount").asInt());
        assertTrue(card.get("firstUsedOn").isNull());
        assertTrue(card.get("lastUsedOn").isNull());
    }

    @Test
    void accountWithoutCardholdersReturnsEmptyList() throws Exception {
        UUID noHolders = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bare Bank", "last4", "5555",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));

        JsonNode stats = api.getJson("/api/v1/accounts/" + noHolders + "/cardholders/stats", 200);

        assertEquals(0, stats.size());
    }

    @Test
    void anotherUsersOrUnknownAccountIsNotFound() throws Exception {
        assertEquals(404, otherApi.get(accountBase + "/stats").getStatus());
        assertEquals(404, api.get("/api/v1/accounts/" + UUID.randomUUID() + "/cardholders/stats").getStatus());
    }

    // helpers

    private JsonNode primaryHolder() throws Exception {
        return api.getJson(accountBase, 200).get(0);
    }

    private UUID primaryHolderId() throws Exception {
        return UUID.fromString(primaryHolder().get("id").asText());
    }

    private UUID primaryCard() throws Exception {
        return UUID.fromString(primaryHolder().get("cards").get(0).get("id").asText());
    }

    private JsonNode primaryStats() throws Exception {
        return api.getJson(accountBase + "/stats", 200).get(0);
    }

    private static JsonNode byHolder(JsonNode array, UUID id) {
        for (JsonNode n : array) {
            if (id.toString().equals(n.get("cardholderId").asText())) {
                return n;
            }
        }
        throw new AssertionError("no stats for holder " + id);
    }

    private static JsonNode byCard(JsonNode holderStats, UUID id) {
        for (JsonNode n : holderStats.get("cards")) {
            if (id.toString().equals(n.get("cardId").asText())) {
                return n;
            }
        }
        throw new AssertionError("no stats for card " + id);
    }

    /** A bank account starts with no cardholder; gives it a primary one with a debit card. */
    private UUID bankDebitCard(UUID bank, String last4) throws Exception {
        JsonNode holder = api.postJson("/api/v1/accounts/" + bank + "/cardholders/primary", Map.of("last4", last4), 201);
        return UUID.fromString(holder.get("cards").get(0).get("id").asText());
    }

    private UUID tx(UUID cardId, LocalDate date, String amount, TransactionType type) {
        return saveTx(accountId, cardId, date, amount, type);
    }

    private UUID saveTx(UUID account, UUID cardId, LocalDate date, String amount, TransactionType type) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(accountRepository.findById(account).orElseThrow());
        t.setCard(cardRepository.findById(cardId).orElseThrow());
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(TransactionSource.manual);
        t.setSourcedDescription("TEST");
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        return transactionRepository.save(t).getId();
    }

    /** Marks the credit as the counterpart of a bank-side bill payment (a CC_PAYMENT link). */
    private void linkAsBillPayment(UUID creditTransactionId) throws Exception {
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Payer Bank", "last4", "9999",
                "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID bankCard = bankDebitCard(bank, "9998");
        UUID debit = saveTx(bank, bankCard, LocalDate.of(2026, 7, 5), "5000", TransactionType.DEBIT);
        UUID linkId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO transaction_links (id, user_id, type, created_by, created_at, updated_at) VALUES (?, ?, 'CC_PAYMENT', 'USER', ?, ?)",
                linkId.toString(), user.getId().toString(), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        jdbc.update("INSERT INTO transaction_link_members (link_id, transaction_id, is_anchor) VALUES (?, ?, TRUE)", linkId.toString(), debit.toString());
        jdbc.update("INSERT INTO transaction_link_members (link_id, transaction_id, is_anchor) VALUES (?, ?, FALSE)", linkId.toString(), creditTransactionId.toString());
    }

    private void statement(LocalDate periodEnd) {
        Account card = accountRepository.findById(accountId).orElseThrow();
        Map<String, Object> cardFields = new HashMap<>();
        cardFields.put("total_amount_due", BigDecimal.ZERO);
        cardFields.put("minimum_amount_due", BigDecimal.ZERO);
        cardFields.put("payment_due_date", periodEnd.plusDays(18));
        cardFields.put("credit_limit", new BigDecimal("200000"));
        StatementDraft draft = new StatementDraft("credit_card", periodEnd.minusDays(30), periodEnd, null, BigDecimal.ZERO,
                "HDFC Bank", "XXXX1111", 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, "heuristic", BigDecimal.ZERO, null,
                StatementVerdict.AUTO_INGEST, cardFields);
        statementPersistenceService.createIfNew(user, card, StatementSource.file_upload,
                "card-" + periodEnd + ".pdf", "sha-" + periodEnd, draft).orElseThrow();
    }
}
