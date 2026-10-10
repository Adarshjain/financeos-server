package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategory;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row breakdowns end to end over real persistence: each net worth row kind (anchored bank account,
 * anchored credit card, loan, lending, broker) and a positions holding after a real FIFO buy/sell
 * explain the exact value the KPI's underlying data lists for that row, the chain reconciling in
 * BigDecimal, with sections paged by the section endpoint; other users' and unknown rows are 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RowBreakdownIntegrationTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String NET_WORTH = "/api/v1/report/datasource/net_worth/rows/";
    private static final String POSITIONS = "/api/v1/report/datasource/positions/rows/";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementRepository statementRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private HoldingRepository holdingRepository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String email = "breakdown-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "breakdown-it-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ------------------------------------------------------------------ bank account

    @Test
    void anchoredBankAccount_startsAtTheStatementAndCountsOnlyTransactionsAfterIt() throws Exception {
        LocalDate anchor = today.minusDays(10);
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Anchor Bank", "last4", "1111",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        statement(bank, anchor, "5000");
        txn(bank, anchor.minusDays(5), "400", "Before anchor", TransactionType.CREDIT, false);
        txn(bank, anchor, "250", "On anchor", TransactionType.DEBIT, false);
        UUID credit = txn(bank, anchor.plusDays(1), "1200", "Refund in", TransactionType.CREDIT, false);
        UUID excluded = txn(bank, anchor.plusDays(2), "300", "Excluded spend", TransactionType.DEBIT, true);
        UUID food = txn(bank, anchor.plusDays(3), "150", "Lunch", TransactionType.DEBIT, false, "Food");

        JsonNode body = api.getJson(NET_WORTH + bank + "/breakdown", 200);

        String since = DAY.format(anchor);
        assertReconciles(body);
        assertListedValue(bank, body);
        assertDecimal("5750", body.get("total"));
        assertEquals("Anchor Bank", body.get("title").asText());
        assertEquals("Asset", body.get("subtitle").asText());
        assertEquals("Bank account", body.get("kindLabel").asText());
        assertEquals("Balance", body.get("totalLabel").asText());
        assertEquals(today.toString(), body.get("asOf").asText());
        assertEquals(List.of(
                        step("start", "Closing balance on statement ending " + since, "5000"),
                        step("add", "Credits after " + since + " (1)", "1200"),
                        step("subtract", "Debits after " + since + " (2)", "450"),
                        step("equals", "Balance", "5750")),
                steps(body));
        List<String> notes = texts(body.get("notes"));
        assertEquals(2, notes.size());
        assertTrue(notes.get(0).startsWith("Opening balance plus all transactions differs from the statement-anchored balance by ₹"),
                notes.get(0));
        assertEquals("Excluded transactions still count towards balances.", notes.get(1));

        JsonNode section = body.get("sections").get(0);
        assertEquals("transactions", section.get("key").asText());
        assertEquals("Transactions after " + since, section.get("label").asText());
        assertEquals("transaction", section.get("rowAction").asText());
        assertTrue(section.get("rowBreakdownDatasource").isNull());
        JsonNode table = section.get("table");
        assertEquals(List.of(food, excluded, credit), rowIds(table));
        assertEquals(List.of("date", "description", "category", "amount", "excluded"), columnKeys(table));
        JsonNode lunch = table.get("rows").get(0);
        assertEquals("Food", lunch.get("category").asText());
        assertDecimal("-150", lunch.get("amount"));
        assertFalse(lunch.get("excluded").asBoolean());
        assertTrue(table.get("rows").get(1).get("excluded").asBoolean());
        assertDecimal("1200", table.get("rows").get(2).get("amount"));
    }

    @Test
    void anchoredBankAccount_sectionPagesFollowTheSameOrder() throws Exception {
        LocalDate anchor = today.minusDays(10);
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Paged Bank", "last4", "2222",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        statement(bank, anchor, "100");
        UUID first = txn(bank, anchor.plusDays(1), "10", "One", TransactionType.CREDIT, false);
        UUID second = txn(bank, anchor.plusDays(2), "20", "Two", TransactionType.CREDIT, false);
        UUID third = txn(bank, anchor.plusDays(3), "30", "Three", TransactionType.CREDIT, false);

        JsonNode breakdown = api.getJson(NET_WORTH + bank + "/breakdown?size=2", 200);
        JsonNode page1 = api.getJson(NET_WORTH + bank + "/breakdown/sections/transactions?page=1&size=2", 200);
        JsonNode capped = api.getJson(NET_WORTH + bank + "/breakdown/sections/transactions?size=1000", 200);

        JsonNode page0 = breakdown.get("sections").get(0).get("table");
        assertEquals(List.of(third, second), rowIds(page0));
        assertEquals(List.of(first), rowIds(page1));
        assertEquals(3, page1.get("page").get("totalElements").asLong());
        assertEquals(2, page1.get("page").get("totalPages").asInt());
        assertEquals(1, page1.get("page").get("number").asInt());
        assertEquals(200, capped.get("page").get("size").asInt());
        assertEquals(List.of(third, second, first), rowIds(capped));
        // Without excluded transactions there is no excluded note.
        assertFalse(texts(breakdown.get("notes")).contains("Excluded transactions still count towards balances."));
    }

    // ------------------------------------------------------------------ credit card

    @Test
    void anchoredCreditCard_startsAtWhatTheStatementSaysIsOwed() throws Exception {
        LocalDate anchor = today.minusDays(15);
        UUID card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Travel Card", "last4", "4321",
                "creditLimit", 200000, "anniversaryDate", today.toString(), "financialPosition", "liability",
                "excludeFromNetAsset", false));
        statement(card, anchor, "8000");
        txn(card, anchor.minusDays(3), "999", "Billed spend", TransactionType.DEBIT, false);
        UUID spend = txn(card, anchor.plusDays(2), "1500", "Flight", TransactionType.DEBIT, false);
        UUID payment = txn(card, anchor.plusDays(5), "8000", "Bill payment", TransactionType.CREDIT, false);

        JsonNode body = api.getJson(NET_WORTH + card + "/breakdown", 200);

        String since = DAY.format(anchor);
        assertReconciles(body);
        assertListedValue(card, body);
        assertDecimal("1500", body.get("total"));
        assertEquals("Liability", body.get("subtitle").asText());
        assertEquals("Credit card", body.get("kindLabel").asText());
        assertEquals(List.of(
                        step("start", "Owed on statement ending " + since, "8000"),
                        step("add", "Spends since " + since + " (1)", "1500"),
                        step("subtract", "Payments and refunds since " + since + " (1)", "8000"),
                        step("equals", "Outstanding", "1500")),
                steps(body));
        assertEquals(List.of(payment, spend), rowIds(body.get("sections").get(0).get("table")));
    }

    // ------------------------------------------------------------------ loan

    @Test
    void loan_principalLessRepaidEmisAndCountedPrepaymentsIsTheOutstanding() throws Exception {
        LocalDate firstEmi = today.minusMonths(2).minusDays(5);
        Map<String, Object> loanBody = new HashMap<>();
        loanBody.put("name", "Car loan");
        loanBody.put("loanType", "car");
        loanBody.put("lender", "HDFC");
        loanBody.put("principal", "120000");
        loanBody.put("annualRatePct", "9.5");
        loanBody.put("rateType", "fixed");
        loanBody.put("tenureMonths", 24);
        loanBody.put("startDate", firstEmi.minusMonths(1).toString());
        loanBody.put("firstEmiDate", firstEmi.toString());
        UUID loan = api.create("/api/v1/loans", loanBody);
        Map<String, Object> prepayment = new HashMap<>();
        prepayment.put("eventType", "prepayment");
        prepayment.put("effectiveDate", firstEmi.plusDays(10).toString());
        prepayment.put("amount", "10000");
        prepayment.put("adjustmentMode", "reduce_tenure");
        assertEquals(2, api.post("/api/v1/loans/" + loan + "/events", prepayment).getStatus() / 100);

        JsonNode body = api.getJson(NET_WORTH + loan + "/breakdown", 200);

        assertReconciles(body);
        assertListedValue(loan, body);
        assertEquals("Liability", body.get("subtitle").asText());
        assertEquals("Loan", body.get("kindLabel").asText());
        List<List<String>> steps = steps(body);
        assertEquals(List.of("start", "Loan principal", "120000"), steps.get(0));
        assertEquals("subtract", steps.get(1).get(0));
        assertTrue(steps.get(1).get(1).matches("Principal repaid through \\d+ EMIs due so far"), steps.get(1).get(1));
        assertEquals(List.of("subtract", "Prepayments (1)", "10000"), steps.get(2));
        assertEquals("equals", steps.get(3).get(0));
        assertTrue(steps.get(3).get(1).startsWith("Outstanding after EMI #"), steps.get(3).get(1));

        int due = Integer.parseInt(steps.get(1).get(1).replaceAll("\\D", ""));
        JsonNode installments = section(body, "installments");
        assertEquals(due, installments.get("page").get("totalElements").asInt());
        BigDecimal repaid = BigDecimal.ZERO;
        for (JsonNode row : installments.get("rows")) {
            assertFalse(LocalDate.parse(row.get("dueDate").asText()).isAfter(today));
            repaid = repaid.add(row.get("principal").decimalValue());
        }
        assertEquals(0, repaid.compareTo(new BigDecimal(steps.get(1).get(2))));
        JsonNode prepayments = section(body, "prepayments");
        assertEquals(1, prepayments.get("rows").size());
        assertTrue(prepayments.get("rows").get(0).get("counted").asBoolean());
        JsonNode viaSection = api.getJson(NET_WORTH + loan + "/breakdown/sections/installments?size=1", 200);
        assertEquals(installments.get("rows").get(0).get("seq").asInt(), viaSection.get("rows").get(0).get("seq").asInt());
    }

    // ------------------------------------------------------------------ lending

    @Test
    void lending_whatYouLentLessWhatTheyPaidBackIsWhatTheyOwe() throws Exception {
        UUID counterparty = lending(null, "Ravi", "lent", "principal", "5000", today.minusDays(20));
        lending(counterparty, null, "borrowed", "settlement", "2000", today.minusDays(5));

        JsonNode body = api.getJson(NET_WORTH + counterparty + "/breakdown", 200);

        assertReconciles(body);
        assertListedValue(counterparty, body);
        assertEquals(List.of(
                        step("add", "You lent / paid them (1)", "5000"),
                        step("subtract", "They paid you / you borrowed (1)", "2000"),
                        step("equals", "They owe you", "3000")),
                steps(body));
        assertEquals("Lending", body.get("kindLabel").asText());
        JsonNode entries = section(body, "entries");
        assertEquals(List.of("Borrowed", "Lent"), column(entries, "direction"));
        assertEquals(List.of("Settlement", "Principal"), column(entries, "kind"));
    }

    // ------------------------------------------------------------------ broker + positions

    @Test
    void broker_cashPlusHoldingsAtMarketValueOpensThePositionsBreakdown() throws Exception {
        Fixture f = brokerWithFifoTrades();

        JsonNode body = api.getJson(NET_WORTH + f.broker + "/breakdown", 200);

        assertReconciles(body);
        assertListedValue(f.broker, body);
        assertEquals(List.of(
                        step("start", "Cash balance", "5000"),
                        step("add", "Holdings at market value (1)", "1050"),
                        step("equals", "Balance", "6050")),
                steps(body));
        JsonNode section = body.get("sections").get(0);
        assertEquals("holdings", section.get("key").asText());
        assertEquals("breakdown", section.get("rowAction").asText());
        assertEquals("positions", section.get("rowBreakdownDatasource").asText());
        JsonNode holdingRow = section.get("table").get("rows").get(0);
        assertEquals(f.holding.toString(), holdingRow.get("id").asText());
        assertDecimal("7", holdingRow.get("quantity"));
        assertDecimal("150", holdingRow.get("price"));
        assertDecimal("1050", holdingRow.get("value"));
        assertTrue(holdingRow.get("valuation").isNull());
    }

    @Test
    void positions_fifoLotsAndHistoryExplainTheHoldingsListedValue() throws Exception {
        Fixture f = brokerWithFifoTrades();
        JsonNode underlying = api.postJson("/api/v1/reports/underlying", Map.of("type", "KPI", "datasource", "positions",
                "definition", Map.of("measure", "currentValue", "aggregation", "sum", "filters", List.of())), 200);
        assertEquals("breakdown", underlying.get("rowAction").asText());
        JsonNode listed = underlying.get("table").get("rows").get(0);
        assertEquals(f.holding.toString(), listed.get("id").asText());

        JsonNode body = api.getJson(POSITIONS + f.holding + "/breakdown", 200);

        assertReconciles(body);
        assertEquals(0, listed.get("currentValue").decimalValue().compareTo(body.get("total").decimalValue()));
        assertDecimal("1050", body.get("total"));
        assertEquals("positions", body.get("datasource").asText());
        assertEquals("FIFO Ltd", body.get("title").asText());
        assertEquals("FIFO Broker", body.get("subtitle").asText());
        assertEquals("Stock", body.get("kindLabel").asText());
        List<List<String>> steps = steps(body);
        assertEquals(List.of("info", "Open quantity", "7"), steps.get(0));
        assertEquals("Average cost", steps.get(1).get(1));
        assertEquals(List.of("start", "Cost of open lots", "800"), steps.get(2));
        assertEquals(List.of("add", "Unrealised gain", "250"), steps.get(3));
        assertEquals(List.of("equals", "Current value", "1050"), steps.get(4));
        assertEquals(List.of("info", "Latest price", "150"), steps.get(5));
        assertEquals(List.of("info", "Realised P&L", "240"), steps.get(6));

        JsonNode lots = section(body, "lots");
        assertEquals(List.of("2", "5"), decimals(lots, "quantity"));
        assertEquals(List.of("200", "600"), decimals(lots, "cost"));
        assertEquals(List.of(f.firstBuy.toString(), f.secondBuy.toString()), column(lots, "buyDate"));
        JsonNode history = section(body, "history");
        assertEquals(List.of("Buy", "Buy", "Sell"), column(history, "event"));
        assertEquals(List.of("10", "15", "7"), decimals(history, "quantityAfter"));
        JsonNode historyPage = api.getJson(POSITIONS + f.holding + "/breakdown/sections/history?page=1&size=2", 200);
        assertEquals(List.of("Sell"), column(historyPage, "event"));
    }

    // ------------------------------------------------------------------ not found / not supported

    @Test
    void anotherUsersRowsAndUnknownRowsOrSectionsAreNotFound() throws Exception {
        Fixture f = brokerWithFifoTrades();
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Mine", "last4", "3333",
                "openingBalance", 10, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID hidden = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Hidden",
                "financialPosition", "asset", "excludeFromNetAsset", true));

        assertNotFound(otherApi.get(NET_WORTH + bank + "/breakdown"));
        assertNotFound(otherApi.get(NET_WORTH + bank + "/breakdown/sections/transactions"));
        assertNotFound(otherApi.get(POSITIONS + f.holding + "/breakdown"));
        assertNotFound(otherApi.get(POSITIONS + f.holding + "/breakdown/sections/lots"));
        assertNotFound(api.get(NET_WORTH + UUID.randomUUID() + "/breakdown"));
        assertNotFound(api.get(NET_WORTH + "not-a-uuid/breakdown"));
        assertNotFound(api.get(POSITIONS + UUID.randomUUID() + "/breakdown"));
        // An excluded account is explained to its owner (flagged not counted) but never to another user.
        assertNotFound(otherApi.get(NET_WORTH + hidden + "/breakdown"));
        assertNotFound(api.get(NET_WORTH + bank + "/breakdown/sections/holdings"));
        assertNotFound(api.get(POSITIONS + f.holding + "/breakdown/sections/nope"));
    }

    @Test
    void datasourceWithoutABreakdownOrUnknownIs400() throws Exception {
        MockHttpServletResponse transactions = api.get("/api/v1/report/datasource/transactions/rows/" + UUID.randomUUID() + "/breakdown");
        MockHttpServletResponse unknown = api.get("/api/v1/report/datasource/nope/rows/" + UUID.randomUUID() + "/breakdown");
        MockHttpServletResponse section = api.get("/api/v1/report/datasource/transactions/rows/x/breakdown/sections/y");

        assertEquals(400, transactions.getStatus());
        assertTrue(transactions.getContentAsString().contains("No breakdown for Transactions"));
        assertEquals(400, unknown.getStatus());
        assertEquals(400, section.getStatus());
    }

    // ------------------------------------------------------------------ fixtures

    private record Fixture(UUID broker, UUID holding, LocalDate firstBuy, LocalDate secondBuy) {
    }

    /** Buy 10 @ 100, buy 5 @ 120, sell 8 @ 130 → open lots 2 @ 100 + 5 @ 120; priced at 150. */
    private Fixture brokerWithFifoTrades() throws Exception {
        UUID broker = api.create("/api/v1/accounts", Map.of("type", "broker", "name", "FIFO Broker", "provider", "Zerodha",
                "clientId", "CL1", "cashBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", false));
        String symbol = "FF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID instrument = api.create("/api/v1/instruments", Map.of("type", "stock", "name", "FIFO Ltd",
                "symbol", symbol, "exchange", "NSE"));
        instrumentIds.add(instrument);
        LocalDate firstBuy = today.minusDays(30);
        LocalDate secondBuy = today.minusDays(20);
        trade(broker, instrument, "buy", "10", "100", firstBuy);
        trade(broker, instrument, "buy", "5", "120", secondBuy);
        trade(broker, instrument, "sell", "8", "130", today.minusDays(10));
        api.postJson("/api/v1/instruments/" + instrument + "/price",
                Map.of("price", 150, "asOf", today.minusDays(1).toString()), 200);
        UUID holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(broker, instrument).orElseThrow().getId();
        return new Fixture(broker, holding, firstBuy, secondBuy);
    }

    private void trade(UUID broker, UUID instrument, String type, String quantity, String price, LocalDate date)
            throws Exception {
        api.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", quantity, "price", price,
                "tradeDate", date.toString()));
    }

    private UUID lending(UUID counterpartyId, String newName, String direction, String kind, String amount, LocalDate date)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (counterpartyId != null) {
            body.put("counterpartyId", counterpartyId.toString());
        } else {
            body.put("newCounterpartyName", newName);
        }
        body.put("direction", direction);
        body.put("kind", kind);
        body.put("amount", amount);
        body.put("entryDate", date.toString());
        JsonNode created = api.postJson("/api/v1/lendings", body, 200);
        return UUID.fromString(created.get("counterpartyId").asText());
    }

    private void statement(UUID accountId, LocalDate periodEnd, String closingBalance) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement s = new Statement();
        s.setUser(account.getUser());
        s.setAccount(account);
        s.setSource(StatementSource.file_upload);
        s.setStatementType(account.getType().name());
        s.setPeriodStart(periodEnd.minusMonths(1).plusDays(1));
        s.setPeriodEnd(periodEnd);
        s.setClosingBalance(new BigDecimal(closingBalance));
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        statementRepository.save(s);
    }

    private UUID txn(UUID accountId, LocalDate date, String amount, String description, TransactionType type,
                     boolean excluded) {
        return txn(accountId, date, amount, description, type, excluded, null);
    }

    private UUID txn(UUID accountId, LocalDate date, String amount, String description, TransactionType type,
                     boolean excluded, String categoryName) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), description,
                TransactionSource.manual, type, false, excluded);
        t.setUser(account.getUser());
        if (categoryName != null) {
            Category category = categoryRepository.save(new Category(categoryName, account.getUser()));
            t.getCategories().add(new TransactionCategory(t, category));
        }
        return transactionRepository.save(t).getId();
    }

    /** The row's value as the net worth KPI's underlying data lists it (positive, on its side). */
    private BigDecimal listedValue(UUID rowId) throws Exception {
        JsonNode body = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying?size=100", null, 200);
        for (JsonNode row : body.get("table").get("rows")) {
            if (row.get("id").asText().equals(rowId.toString())) {
                return row.get("signedValue").decimalValue().abs();
            }
        }
        throw new AssertionError("row " + rowId + " is not listed: " + body.get("table"));
    }

    // ------------------------------------------------------------------ assertions

    /** The breakdown's total is exactly the value the KPI's underlying data lists for the row. */
    private void assertListedValue(UUID rowId, JsonNode body) throws Exception {
        BigDecimal listed = listedValue(rowId);
        assertEquals(0, listed.compareTo(body.get("total").decimalValue()), listed + " vs " + body.get("total"));
    }

    /** start ± add/subtract = equals = total, exactly. */
    private static void assertReconciles(JsonNode body) {
        BigDecimal running = BigDecimal.ZERO;
        BigDecimal equals = null;
        for (JsonNode step : body.get("steps")) {
            String op = step.get("op").asText();
            switch (op) {
                case "start", "add" -> running = running.add(step.get("amount").decimalValue());
                case "subtract" -> {
                    assertTrue(step.get("amount").decimalValue().signum() >= 0, "subtract carries a magnitude");
                    running = running.subtract(step.get("amount").decimalValue());
                }
                case "equals" -> equals = step.get("amount").decimalValue();
                case "info" -> { }
                default -> throw new AssertionError("unknown op " + op);
            }
        }
        assertNotNull(equals, "chain has an equals step");
        assertEquals(0, running.compareTo(equals), "chain " + running + " vs equals " + equals);
        assertEquals(0, equals.compareTo(body.get("total").decimalValue()), "equals is the total");
        for (JsonNode step : body.get("steps")) {
            assertFalse(step.get("label").asText().equals("Rounding difference"), "no rounding residual");
        }
    }

    private static void assertNotFound(MockHttpServletResponse response) throws Exception {
        assertEquals(404, response.getStatus(), response.getContentAsString());
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static List<String> step(String op, String label, String amount) {
        return List.of(op, label, amount);
    }

    /** Each step as [op, label, amount (plain, no trailing zeros)]; info steps without an amount get "". */
    private static List<List<String>> steps(JsonNode body) {
        List<List<String>> steps = new ArrayList<>();
        for (JsonNode s : body.get("steps")) {
            JsonNode amount = s.get("amount");
            steps.add(List.of(s.get("op").asText(), s.get("label").asText(),
                    amount == null || amount.isNull() ? "" : amount.decimalValue().stripTrailingZeros().toPlainString()));
        }
        return steps;
    }

    private static JsonNode section(JsonNode body, String key) {
        for (JsonNode s : body.get("sections")) {
            if (key.equals(s.get("key").asText())) {
                return s.get("table");
            }
        }
        throw new AssertionError("no section " + key);
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static List<String> columnKeys(JsonNode table) {
        List<String> keys = new ArrayList<>();
        table.get("columns").forEach(c -> keys.add(c.get("key").asText()));
        return keys;
    }

    private static List<UUID> rowIds(JsonNode table) {
        List<UUID> ids = new ArrayList<>();
        table.get("rows").forEach(r -> ids.add(UUID.fromString(r.get("id").asText())));
        return ids;
    }

    private static List<String> column(JsonNode table, String key) {
        List<String> values = new ArrayList<>();
        table.get("rows").forEach(r -> values.add(r.get(key).asText()));
        return values;
    }

    private static List<String> decimals(JsonNode table, String key) {
        List<String> values = new ArrayList<>();
        table.get("rows").forEach(r -> values.add(r.get(key).decimalValue().stripTrailingZeros().toPlainString()));
        return values;
    }
}
