package com.financeos.api.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.card.Cardholder;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.account.card.CardholderRole;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account insight reads (tab counts, flow summary, payments-from, Gmail summary) over real
 * persistence: one user's bank account with a known set of rows, and a second user who gets 404s.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountInsightsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementRepository statementRepository;
    @Autowired private CardholderRepository cardholderRepository;
    @Autowired private GmailConnectionRepository connectionRepository;
    @Autowired private GmailProcessedMessageRepository processedMessageRepository;

    private final LocalDate today = AppTime.today();
    private ApiTestClient api;
    private ApiTestClient otherApi;
    private User user;
    private UUID userId;
    private UUID otherUserId;
    private UUID bank;
    private UUID emptyBank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "account-insights-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        userId = user.getId();
        String otherEmail = "account-insights-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        bank = bank("Main Bank", "1111");
        emptyBank = bank("Empty Bank", "2222");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    // ------------------------------------------------------------------ tab counts

    @Test
    void tabCountsCountStatementsOpenCardholdersAndEveryTransactionTheListShows() throws Exception {
        txn(bank, today.minusDays(1), 100, "a", List.of());
        txn(bank, today.minusDays(2), -50, "b", List.of());
        UUID excluded = txn(bank, today.minusDays(3), -10, "excluded", List.of());
        jdbc.update("UPDATE transactions SET is_excluded = TRUE WHERE id = ?", excluded.toString());
        txn(emptyBank, today, 999, "another account", List.of());
        statement(bank, today.minusDays(40), today.minusDays(10));
        statement(bank, today.minusDays(70), today.minusDays(41));
        statement(emptyBank, today.minusDays(40), today.minusDays(10));
        cardholder(bank, CardholderRole.PRIMARY, null);
        cardholder(bank, CardholderRole.ADDON, null);
        cardholder(bank, CardholderRole.ADDON, today.minusDays(5));

        JsonNode counts = api.getJson("/api/v1/accounts/" + bank + "/tab-counts", 200);

        assertEquals(2, counts.get("statements").asLong());
        assertEquals(2, counts.get("cards").asLong(), "the closed cardholder is not counted");
        assertEquals(3, counts.get("transactions").asLong(), "the excluded row is in the list, so it is counted");
        Map<String, Object> search = Map.of("filters", List.of(Map.of("field", "accountId", "operator", "is", "value", bank.toString())));
        JsonNode listed = api.postJson("/api/v1/transactions/search", search, 200);
        assertEquals(counts.get("transactions").asLong(), listed.get("totalElements").asLong());
    }

    @Test
    void tabCountsOfAnEmptyAccountAreZero() throws Exception {
        JsonNode counts = api.getJson("/api/v1/accounts/" + emptyBank + "/tab-counts", 200);

        assertEquals(0, counts.get("statements").asLong());
        assertEquals(0, counts.get("cards").asLong());
        assertEquals(0, counts.get("transactions").asLong());
    }

    // ------------------------------------------------------------------ flow summary

    @Test
    void flowSplitsInAndOutAndRanksCategoriesWithinTheWindow() throws Exception {
        UUID salary = category("Salary");
        UUID bonus = category("Bonus");
        UUID gift = category("Gift");
        UUID rent = category("Rent");
        UUID food = category("Food");
        UUID fuel = category("Fuel");
        UUID misc = category("Misc");
        txn(bank, today.minusDays(1), 5000, "pay", List.of(salary));
        txn(bank, today.minusDays(2), 200, "bonus", List.of(bonus));
        txn(bank, today.minusDays(3), 200, "gift", List.of(gift));
        txn(bank, today.minusDays(4), 700, "no category", List.of());
        txn(bank, today.minusDays(89), 40, "first day of the window", List.of());
        txn(bank, today.minusDays(90), 9999, "just outside", List.of(salary));
        txn(bank, today.minusDays(1), -1000, "rent", List.of(rent));
        txn(bank, today.minusDays(2), -500, "food", List.of(food));
        txn(bank, today.minusDays(3), -300, "fuel", List.of(fuel));
        txn(bank, today.minusDays(4), -100, "misc", List.of(misc));
        txn(bank, today.minusDays(5), -50, "no category", List.of());
        txn(emptyBank, today.minusDays(1), 123456, "another account", List.of(salary));

        JsonNode flow = api.getJson("/api/v1/accounts/" + bank + "/flow-summary", 200);

        assertEquals(today.toString(), flow.get("to").asText());
        assertEquals(today.minusDays(89).toString(), flow.get("from").asText());
        assertEquals(0, new BigDecimal("6140").compareTo(flow.get("moneyIn").decimalValue()));
        assertEquals(0, new BigDecimal("1950").compareTo(flow.get("moneyOut").decimalValue()));
        assertEquals(List.of("Salary", "Bonus"), names(flow.get("topInCategories")));
        assertEquals(List.of("Rent", "Food", "Fuel"), names(flow.get("topOutCategories")));
    }

    @Test
    void flowOfAnEmptyAccountIsZeroWithNoCategories() throws Exception {
        JsonNode flow = api.getJson("/api/v1/accounts/" + emptyBank + "/flow-summary?days=30", 200);

        assertEquals(today.minusDays(29).toString(), flow.get("from").asText());
        assertEquals(0, BigDecimal.ZERO.compareTo(flow.get("moneyIn").decimalValue()));
        assertEquals(0, BigDecimal.ZERO.compareTo(flow.get("moneyOut").decimalValue()));
        assertEquals(0, flow.get("topInCategories").size());
        assertEquals(0, flow.get("topOutCategories").size());
        assertEquals(0, BigDecimal.ZERO.compareTo(flow.get("interestLastQuarter").decimalValue()));
    }

    @Test
    void interestIsTheInterestCreditsOfThePreviousCalendarQuarterOnly() throws Exception {
        UUID interest = category("Interest");
        jdbc.update("UPDATE categories SET name = ? WHERE id = ?", "  InTeReSt ", interest.toString());
        UUID salary = category("Salary");
        LocalDate currentQuarterStart = LocalDate.of(today.getYear(), ((today.getMonthValue() - 1) / 3) * 3 + 1, 1);
        LocalDate from = currentQuarterStart.minusMonths(3);
        LocalDate to = currentQuarterStart.minusDays(1);
        txn(bank, from, 40, "first day of the quarter", List.of(interest));
        txn(bank, to, 60, "last day of the quarter", List.of(interest));
        txn(bank, from.plusDays(10), 500, "salary in the quarter", List.of(salary));
        txn(bank, from.plusDays(11), -25, "interest-named debit", List.of(interest));
        txn(bank, from.minusDays(1), 7, "the quarter before", List.of(interest));
        txn(bank, currentQuarterStart, 11, "this quarter", List.of(interest));

        JsonNode flow = api.getJson("/api/v1/accounts/" + bank + "/flow-summary", 200);

        assertEquals(from.toString(), flow.get("interestFrom").asText());
        assertEquals(to.toString(), flow.get("interestTo").asText());
        assertEquals(0, new BigDecimal("100").compareTo(flow.get("interestLastQuarter").decimalValue()));
    }

    @Test
    void averageDailyBalanceIsTheMeanOfTheBalanceSeriesOverTheWindow() throws Exception {
        txn(bank, today.minusDays(10), 500, "deposit", List.of());
        txn(bank, today.minusDays(3), -120, "spend", List.of());

        JsonNode flow = api.getJson("/api/v1/accounts/" + bank + "/flow-summary?days=30", 200);

        JsonNode series = api.getJson("/api/v1/accounts/" + bank + "/balance-series?days=30", 200);
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode point : series) {
            sum = sum.add(point.get("balance").decimalValue());
        }
        BigDecimal mean = sum.divide(BigDecimal.valueOf(series.size()), 2, java.math.RoundingMode.HALF_UP);
        assertEquals(30, series.size());
        assertEquals(0, mean.compareTo(flow.get("averageDailyBalance").decimalValue()));
    }

    @Test
    void flowDaysOutOfRangeAreRejected() throws Exception {
        assertEquals(400, api.get("/api/v1/accounts/" + bank + "/flow-summary?days=0").getStatus());
        assertEquals(400, api.get("/api/v1/accounts/" + bank + "/flow-summary?days=367").getStatus());
        assertEquals(200, api.get("/api/v1/accounts/" + bank + "/flow-summary?days=366").getStatus());
    }

    // ------------------------------------------------------------------ payments from

    @Test
    void paymentsFromListsOnlyActiveLoansPayingFromThisAccountByNextDueDate() throws Exception {
        loan("Zeta loan", bank, today.plusDays(20));
        loan("Alpha loan", bank, today.plusDays(20));
        UUID beta = loan("Beta loan", bank, today.plusDays(5));
        UUID closed = loan("Closed loan", bank, today.plusDays(2));
        jdbc.update("UPDATE loans SET status = 'closed' WHERE id = ?", closed.toString());
        loan("Other bank loan", emptyBank, today.plusDays(1));
        loan("No payment account loan", null, today.plusDays(1));

        JsonNode emis = api.getJson("/api/v1/accounts/" + bank + "/payments-from", 200).get("emis");

        assertEquals(List.of("Beta loan", "Alpha loan", "Zeta loan"), emiNames(emis));
        assertEquals(beta.toString(), emis.get(0).get("loanId").asText());
        assertEquals(today.plusDays(5).toString(), emis.get(0).get("nextDueDate").asText());
        assertTrue(emis.get(0).get("amount").decimalValue().signum() > 0);
    }

    @Test
    void cardBillsAreCcPaymentsFromThisAccountLatestPerCardWithinTwelveMonths() throws Exception {
        UUID cardA = card("Card A", "3001");
        UUID cardB = card("Card B", "3002");
        UUID cardOld = card("Card Old", "3003");
        UUID cardTransferOnly = card("Card Transfer", "3004");
        UUID otherBank = bank("Other Bank", "4444");
        ccPayment(bank, cardA, today.minusMonths(3), 500);
        ccPayment(bank, cardA, today.minusMonths(1), 700);
        ccPayment(bank, cardB, today.minusMonths(5), 900);
        ccPayment(bank, cardOld, today.minusMonths(13), 300);
        ccPayment(otherBank, cardB, today.minusDays(2), 111);
        link("TRANSFER", txn(bank, today.minusDays(4), -250, "transfer out", List.of()),
                txn(cardTransferOnly, today.minusDays(4), 250, "transfer in", List.of()));

        JsonNode bills = api.getJson("/api/v1/accounts/" + bank + "/payments-from", 200).get("cardBills");

        assertEquals(2, bills.size());
        assertEquals(cardA.toString(), bills.get(0).get("accountId").asText());
        assertEquals("Card A", bills.get(0).get("name").asText());
        assertEquals(today.minusMonths(1).toString(), bills.get(0).get("lastPaidOn").asText());
        assertEquals(0, new BigDecimal("700").compareTo(bills.get(0).get("lastAmount").decimalValue()));
        assertEquals(cardB.toString(), bills.get(1).get("accountId").asText());
        assertEquals(today.minusMonths(5).toString(), bills.get(1).get("lastPaidOn").asText());
        assertEquals(0, new BigDecimal("900").compareTo(bills.get(1).get("lastAmount").decimalValue()));
    }

    @Test
    void paymentsFromOfAnEmptyAccountIsEmpty() throws Exception {
        JsonNode body = api.getJson("/api/v1/accounts/" + emptyBank + "/payments-from", 200);

        assertEquals(0, body.get("emis").size());
        assertEquals(0, body.get("cardBills").size());
    }

    // ------------------------------------------------------------------ gmail summary

    @Test
    void lastAlertIsTheLatestGmailAlertTransactionOnly() throws Exception {
        UUID older = txn(bank, today.minusDays(5), -100, "Older alert", List.of());
        UUID newerDateEarlierCreated = txn(bank, today.minusDays(2), -3600, "BigBasket", List.of());
        UUID newerDateLaterCreated = txn(bank, today.minusDays(2), 40, "Later alert same day", List.of());
        txn(bank, today, -9, "Manual entry", List.of());
        UUID otherAccountAlert = txn(emptyBank, today, -1, "Alert elsewhere", List.of());
        for (UUID id : List.of(older, newerDateEarlierCreated, newerDateLaterCreated, otherAccountAlert)) {
            jdbc.update("UPDATE transactions SET source = 'gmail_transaction_alert' WHERE id = ?", id.toString());
        }
        jdbc.update("UPDATE transactions SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")), newerDateEarlierCreated.toString());
        jdbc.update("UPDATE transactions SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T11:00:00Z")), newerDateLaterCreated.toString());

        JsonNode summary = api.getJson("/api/v1/accounts/" + bank + "/gmail-summary", 200);

        JsonNode last = summary.get("lastAlert");
        assertEquals(newerDateLaterCreated.toString(), last.get("transactionId").asText());
        assertEquals(today.minusDays(2).toString(), last.get("date").asText());
        assertEquals("Later alert same day", last.get("description").asText());
        assertEquals(0, new BigDecimal("40").compareTo(last.get("amount").decimalValue()));
        assertEquals("CREDIT", last.get("type").asText());
    }

    @Test
    void unmatchedCountIsThisAccountsActiveAttentionItemsWithTheDefaultStatuses() throws Exception {
        GmailConnection connection = new GmailConnection();
        connection.setUser(user);
        connection.setEmail("me@gmail.test");
        connection.setEncryptedRefreshToken("token");
        connection = connectionRepository.save(connection);
        Account mine = accountRepository.findById(bank).orElseThrow();
        Account elsewhere = accountRepository.findById(emptyBank).orElseThrow();
        processed(connection, mine, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null);
        processed(connection, mine, GmailProcessedStatus.FAILED_PERMANENT, null);
        processed(connection, mine, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);
        processed(connection, mine, GmailProcessedStatus.FAILED_RETRYABLE, null);
        processed(connection, mine, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, Instant.now());
        processed(connection, mine, GmailProcessedStatus.CREATED, null);
        processed(connection, elsewhere, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN, null);
        processed(connection, null, GmailProcessedStatus.UNRESOLVED_ACCOUNT, null);

        JsonNode summary = api.getJson("/api/v1/accounts/" + bank + "/gmail-summary", 200);

        assertEquals(3, summary.get("unmatchedCount").asLong());
        assertTrue(summary.get("lastAlert").isNull());
        JsonNode attention = api.getJson("/api/v1/gmail/attention?size=50", 200);
        long listedForAccount = 0;
        for (JsonNode item : attention.get("content")) {
            if (bank.toString().equals(item.path("accountId").asText())) {
                listedForAccount++;
            }
        }
        assertEquals(listedForAccount, summary.get("unmatchedCount").asLong(), "matches the attention list's own filter");
    }

    @Test
    void gmailSummaryOfAnEmptyAccountHasNoAlertAndNoItems() throws Exception {
        JsonNode summary = api.getJson("/api/v1/accounts/" + emptyBank + "/gmail-summary", 200);

        assertTrue(summary.get("lastAlert").isNull());
        assertEquals(0, summary.get("unmatchedCount").asLong());
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void anotherUsersAccountIsNotFoundForEveryRead() throws Exception {
        for (String path : List.of("tab-counts", "flow-summary", "payments-from", "gmail-summary")) {
            assertEquals(404, otherApi.get("/api/v1/accounts/" + bank + "/" + path).getStatus(), path);
            assertEquals(404, api.get("/api/v1/accounts/" + UUID.randomUUID() + "/" + path).getStatus(), path);
        }
    }

    // ------------------------------------------------------------------ helpers

    private UUID bank(String name, String last4) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", last4,
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID card(String name, String last4) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", name, "last4", last4,
                "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
    }

    private UUID category(String name) throws Exception {
        return api.create("/api/v1/categories", Map.of("name", name));
    }

    /** Positive amount is a credit, negative a debit. */
    private UUID txn(UUID account, LocalDate date, int amount, String description, List<UUID> categories) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", date.toString());
        body.put("amount", amount);
        body.put("description", description);
        body.put("categoryIds", categories.stream().map(UUID::toString).toList());
        return api.create("/api/v1/transactions", body);
    }

    private void link(String type, UUID anchor, UUID counterpart) throws Exception {
        Map<String, Object> body = Map.of("type", type, "members", List.of(
                Map.of("transactionId", anchor.toString(), "isAnchor", true),
                Map.of("transactionId", counterpart.toString(), "isAnchor", false)));
        assertEquals(2, api.post("/api/v1/transaction-links", body).getStatus() / 100);
    }

    private void ccPayment(UUID from, UUID card, LocalDate date, int amount) throws Exception {
        link("CC_PAYMENT", txn(from, date, -amount, "card bill", List.of()), txn(card, date, amount, "payment received", List.of()));
    }

    private UUID loan(String name, UUID paymentAccount, LocalDate firstEmi) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("loanType", "car");
        body.put("lender", "HDFC");
        body.put("principal", "120000");
        body.put("annualRatePct", "9.5");
        body.put("rateType", "fixed");
        body.put("tenureMonths", 24);
        body.put("startDate", firstEmi.minusMonths(1).toString());
        body.put("firstEmiDate", firstEmi.toString());
        if (paymentAccount != null) {
            body.put("paymentAccountId", paymentAccount.toString());
        }
        return api.create("/api/v1/loans", body);
    }

    private void statement(UUID account, LocalDate start, LocalDate end) {
        Statement statement = new Statement();
        statement.setUser(user);
        statement.setAccount(accountRepository.findById(account).orElseThrow());
        statement.setSource(StatementSource.file_upload);
        statement.setPeriodStart(start);
        statement.setPeriodEnd(end);
        statementRepository.save(statement);
    }

    private void cardholder(UUID account, CardholderRole role, LocalDate closedOn) {
        Cardholder cardholder = new Cardholder();
        cardholder.setUser(user);
        cardholder.setAccount(accountRepository.findById(account).orElseThrow());
        cardholder.setRole(role);
        cardholder.setOpenedOn(today.minusDays(100));
        cardholder.setClosedOn(closedOn);
        cardholderRepository.save(cardholder);
    }

    private void processed(GmailConnection connection, Account account, GmailProcessedStatus status, Instant archivedAt) {
        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setUser(user);
        gpm.setConnection(connection);
        gpm.setAccount(account);
        gpm.setGmailMessageId("msg-" + UUID.randomUUID());
        gpm.setStatus(status);
        gpm.setDiscoveredAt(Instant.now());
        gpm.setArchivedAt(archivedAt);
        processedMessageRepository.save(gpm);
    }

    private static List<String> names(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> emiNames(JsonNode emis) {
        List<String> out = new java.util.ArrayList<>();
        emis.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }
}
