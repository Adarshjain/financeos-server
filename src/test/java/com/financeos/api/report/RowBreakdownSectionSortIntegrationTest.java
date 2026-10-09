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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sort=<column>,<asc|desc>} on breakdown section pages over real persistence: every section
 * of net worth (bank transactions, broker holdings, loan EMIs and prepayments, lending entries) and
 * of positions (open lots, history) is ordered over the whole section before paging, ties in the
 * section's default order, the sort echoed; a key that is not a column or a malformed sort is 400;
 * the breakdown's embedded first pages stay in the default order.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RowBreakdownSectionSortIntegrationTest {

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
    private UUID userId;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private final Map<String, Category> categories = new HashMap<>();
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String email = "section-sort-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ------------------------------------------------------------------ bank transactions

    private record Bank(UUID id, UUID before, UUID lunch, UUID salary, UUID fuel, UUID excluded) {
    }

    /** Anchored 10 days ago; after it (newest first): excluded 300 debit, fuel 75 debit, salary 2000 credit, lunch 150 debit. */
    private Bank bank() throws Exception {
        LocalDate anchor = today.minusDays(10);
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Sort Bank", "last4", "5555",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        statement(bank, anchor, "1000");
        UUID before = txn(bank, anchor.minusDays(2), "9999", "Before anchor", TransactionType.DEBIT, false);
        UUID lunch = txn(bank, anchor.plusDays(1), "150", "lunch", TransactionType.DEBIT, false, "Food");
        UUID salary = txn(bank, anchor.plusDays(2), "2000", "Salary", TransactionType.CREDIT, false);
        UUID fuel = txn(bank, anchor.plusDays(3), "75", "Fuel", TransactionType.DEBIT, false, "Fuel", "Travel");
        UUID excluded = txn(bank, anchor.plusDays(4), "300", "Atm", TransactionType.DEBIT, true);
        return new Bank(bank, before, lunch, salary, fuel, excluded);
    }

    @Test
    void bankTransactions_sortByAmountOverTheWholeSectionThenPage() throws Exception {
        Bank b = bank();
        String section = NET_WORTH + b.id() + "/breakdown/sections/transactions";

        JsonNode asc = api.getJson(section + "?sort=amount,asc&size=50", 200);
        JsonNode page0 = api.getJson(section + "?sort=amount,desc&size=3", 200);
        JsonNode page1 = api.getJson(section + "?sort=amount,desc&size=3&page=1", 200);

        assertEquals(List.of(b.excluded(), b.lunch(), b.fuel(), b.salary()), rowIds(asc));
        assertEquals(List.of(b.salary(), b.fuel(), b.lunch()), rowIds(page0));
        assertEquals(List.of(b.excluded()), rowIds(page1));
        assertEquals(4, page1.get("page").get("totalElements").asLong());
        assertEquals(2, page1.get("page").get("totalPages").asInt());
        assertEquals("amount", page0.get("sortKey").asText());
        assertEquals("desc", page0.get("sortDirection").asText());
        assertFalse(rowIds(asc).contains(b.before()), "only the transactions the balance counts are listed");
    }

    @Test
    void bankTransactions_sortedRowsAreTheSameRowsAsTheDefaultPages() throws Exception {
        Bank b = bank();
        String section = NET_WORTH + b.id() + "/breakdown/sections/transactions";

        JsonNode byDefault = api.getJson(section + "?size=50", 200);
        JsonNode byDescription = api.getJson(section + "?sort=description,asc&size=50", 200);

        assertEquals(List.of(b.excluded(), b.fuel(), b.salary(), b.lunch()), rowIds(byDefault));
        assertFalse(byDefault.has("sortKey"));
        assertEquals(List.of(b.excluded(), b.fuel(), b.lunch(), b.salary()), rowIds(byDescription));
        Map<String, JsonNode> defaultRows = byId(byDefault);
        for (JsonNode row : byDescription.get("rows")) {
            assertEquals(defaultRows.get(row.get("id").asText()), row);
        }
        assertEquals("Fuel, Travel", defaultRows.get(b.fuel().toString()).get("category").asText());
    }

    @Test
    void bankTransactions_sortByCategoryExcludedAndDateKeepTheDefaultOrderOnTiesAndBlanksLast() throws Exception {
        Bank b = bank();
        String section = NET_WORTH + b.id() + "/breakdown/sections/transactions";

        JsonNode category = api.getJson(section + "?sort=category,asc", 200);
        JsonNode excluded = api.getJson(section + "?sort=excluded,desc", 200);
        JsonNode dateAsc = api.getJson(section + "?sort=date,ASC", 200);

        // Uncategorised rows hold "" and sort first ascending; the default order breaks the tie.
        assertEquals(List.of(b.excluded(), b.salary(), b.lunch(), b.fuel()), rowIds(category));
        assertEquals(List.of(b.excluded(), b.fuel(), b.salary(), b.lunch()), rowIds(excluded));
        assertEquals(List.of(b.lunch(), b.salary(), b.fuel(), b.excluded()), rowIds(dateAsc));
        assertEquals("asc", dateAsc.get("sortDirection").asText());
    }

    @Test
    void bankTransactions_embeddedFirstPageStaysInTheDefaultOrder() throws Exception {
        Bank b = bank();

        JsonNode body = api.getJson(NET_WORTH + b.id() + "/breakdown?size=2", 200);

        JsonNode table = body.get("sections").get(0).get("table");
        assertEquals(List.of(b.excluded(), b.fuel()), rowIds(table));
        assertFalse(table.has("sortKey"));
        assertFalse(table.has("sortDirection"));
    }

    @Test
    void aSortKeyThatIsNotAColumnOrAMalformedSortIs400() throws Exception {
        Bank b = bank();
        String section = NET_WORTH + b.id() + "/breakdown/sections/transactions";

        MockHttpServletResponse notAColumn = api.get(section + "?sort=id,asc");
        MockHttpServletResponse malformed = api.get(section + "?sort=amount");
        MockHttpServletResponse badDirection = api.get(section + "?sort=amount,sideways");
        MockHttpServletResponse twoClauses = api.get(section + "?sort=amount,asc,date,desc");

        assertEquals(400, notAColumn.getStatus());
        assertTrue(notAColumn.getContentAsString().contains("Sort key is not an available column: id"));
        assertEquals(400, malformed.getStatus());
        assertTrue(malformed.getContentAsString().contains("Invalid sort 'amount'"));
        assertEquals(400, badDirection.getStatus());
        assertEquals(400, twoClauses.getStatus());
        assertEquals(404, api.get(NET_WORTH + UUID.randomUUID() + "/breakdown/sections/transactions?sort=amount,asc").getStatus());
        assertEquals(404, api.get(NET_WORTH + b.id() + "/breakdown/sections/nope?sort=amount,asc").getStatus());
    }

    // ------------------------------------------------------------------ broker holdings + positions

    @Test
    void brokerHoldings_sortByAnyColumn() throws Exception {
        UUID broker = broker();
        UUID cheap = holding(broker, "Alpha Ltd", "4", "100", "110");
        UUID dear = holding(broker, "Beta Ltd", "2", "1000", "900");
        String section = NET_WORTH + broker + "/breakdown/sections/holdings";

        JsonNode byDefault = api.getJson(section, 200);
        JsonNode byQuantity = api.getJson(section + "?sort=quantity,desc", 200);
        JsonNode byInstrument = api.getJson(section + "?sort=instrument,desc&size=1", 200);

        assertEquals(List.of(dear, cheap), rowIds(byDefault));
        assertEquals(List.of(cheap, dear), rowIds(byQuantity));
        assertEquals(List.of(dear), rowIds(byInstrument));
        assertEquals(2, byInstrument.get("page").get("totalPages").asInt());
        assertEquals("instrument", byInstrument.get("sortKey").asText());
        assertEquals(400, api.get(section + "?sort=amount,asc").getStatus());
    }

    @Test
    void positionsLotsAndHistory_sortByAnyColumn() throws Exception {
        UUID broker = broker();
        String symbol = "SS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID instrument = api.create("/api/v1/instruments", Map.of("type", "stock", "name", "Sorted Ltd",
                "symbol", symbol, "exchange", "NSE"));
        instrumentIds.add(instrument);
        trade(broker, instrument, "buy", "10", "100", today.minusDays(30));
        trade(broker, instrument, "buy", "5", "120", today.minusDays(20));
        trade(broker, instrument, "buy", "1", "90", today.minusDays(15));
        trade(broker, instrument, "sell", "8", "130", today.minusDays(10));
        UUID holding = holdingRepository.findByBrokerAccountIdAndInstrumentId(broker, instrument).orElseThrow().getId();
        String base = POSITIONS + holding + "/breakdown/sections/";

        JsonNode lotsDefault = api.getJson(base + "lots", 200);
        JsonNode lotsByCost = api.getJson(base + "lots?sort=costPerUnit,desc", 200);
        JsonNode historyByChange = api.getJson(base + "history?sort=quantityChange,asc&size=2", 200);
        JsonNode historyPage1 = api.getJson(base + "history?sort=quantityChange,asc&size=2&page=1", 200);

        assertEquals(List.of("100", "120", "90"), decimals(lotsDefault, "costPerUnit"));
        assertEquals(List.of("120", "100", "90"), decimals(lotsByCost, "costPerUnit"));
        assertEquals("costPerUnit", lotsByCost.get("sortKey").asText());
        assertEquals(List.of("Sell", "Buy"), column(historyByChange, "event"));
        assertEquals(List.of("-8", "1"), decimals(historyByChange, "quantityChange"));
        assertEquals(List.of("5", "10"), decimals(historyPage1, "quantityChange"));
        assertEquals(4, historyPage1.get("page").get("totalElements").asLong());
        assertEquals(400, api.get(base + "lots?sort=event,asc").getStatus());
        assertEquals(400, api.get(base + "history?sort=quantityChange").getStatus());
    }

    // ------------------------------------------------------------------ loan

    @Test
    void loanInstallmentsAndPrepayments_sortByAnyColumn() throws Exception {
        LocalDate firstEmi = today.minusMonths(3).minusDays(5);
        Map<String, Object> loanBody = new HashMap<>();
        loanBody.put("name", "Sort loan");
        loanBody.put("loanType", "car");
        loanBody.put("lender", "HDFC");
        loanBody.put("principal", "120000");
        loanBody.put("annualRatePct", "9.5");
        loanBody.put("rateType", "fixed");
        loanBody.put("tenureMonths", 24);
        loanBody.put("startDate", firstEmi.minusMonths(1).toString());
        loanBody.put("firstEmiDate", firstEmi.toString());
        UUID loan = api.create("/api/v1/loans", loanBody);
        prepay(loan, firstEmi.plusDays(10), "5000");
        prepay(loan, firstEmi.plusDays(40), "8000");
        String base = NET_WORTH + loan + "/breakdown/sections/";

        JsonNode installmentsDefault = api.getJson(base + "installments", 200);
        JsonNode installmentsBySeq = api.getJson(base + "installments?sort=seq,asc&size=2", 200);
        JsonNode prepaymentsByAmount = api.getJson(base + "prepayments?sort=amount,asc", 200);
        JsonNode prepaymentsDefault = api.getJson(base + "prepayments", 200);

        List<String> defaultSeqs = column(installmentsDefault, "seq");
        assertTrue(defaultSeqs.size() >= 3, defaultSeqs.toString());
        assertEquals(List.of("1", "2"), column(installmentsBySeq, "seq"));
        assertEquals("seq", installmentsBySeq.get("sortKey").asText());
        assertEquals(defaultSeqs.size(), installmentsBySeq.get("page").get("totalElements").asInt());
        assertEquals(List.of("8000", "5000"), decimals(prepaymentsDefault, "amount"));
        assertEquals(List.of("5000", "8000"), decimals(prepaymentsByAmount, "amount"));
        assertEquals(400, api.get(base + "installments?sort=amount,asc").getStatus());
    }

    // ------------------------------------------------------------------ lending

    @Test
    void lendingEntries_sortByAnyColumnOverEveryEntry() throws Exception {
        UUID counterparty = lending(null, "Ravi", "lent", "principal", "5000", today.minusDays(20));
        lending(counterparty, null, "borrowed", "settlement", "2000", today.minusDays(5));
        lending(counterparty, null, "lent", "principal", "700", today.minusDays(2));
        String section = NET_WORTH + counterparty + "/breakdown/sections/entries";

        JsonNode byDefault = api.getJson(section, 200);
        JsonNode byAmount = api.getJson(section + "?sort=amount,desc&size=2", 200);
        JsonNode byAmountPage1 = api.getJson(section + "?sort=amount,desc&size=2&page=1", 200);
        JsonNode byDirection = api.getJson(section + "?sort=direction,asc", 200);

        assertEquals(List.of("700", "2000", "5000"), decimals(byDefault, "amount"));
        assertEquals(List.of("5000", "2000"), decimals(byAmount, "amount"));
        assertEquals(List.of("700"), decimals(byAmountPage1, "amount"));
        assertEquals(3, byAmountPage1.get("page").get("totalElements").asLong());
        assertEquals(List.of("Borrowed", "Lent", "Lent"), column(byDirection, "direction"));
        assertEquals(List.of("2000", "700", "5000"), decimals(byDirection, "amount"));
        assertEquals("direction", byDirection.get("sortKey").asText());
        assertEquals(400, api.get(section + "?sort=counterparty,asc").getStatus());
    }

    // ------------------------------------------------------------------ fixtures

    private UUID broker() throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "broker", "name", "Sort Broker", "provider", "Zerodha",
                "clientId", "CL" + UUID.randomUUID().toString().substring(0, 4), "cashBalance", 100,
                "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    /** One instrument bought once and priced; returns the holding id. */
    private UUID holding(UUID broker, String name, String quantity, String price, String latest) throws Exception {
        String symbol = "SH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID instrument = api.create("/api/v1/instruments", Map.of("type", "stock", "name", name,
                "symbol", symbol, "exchange", "NSE"));
        instrumentIds.add(instrument);
        trade(broker, instrument, "buy", quantity, price, today.minusDays(10));
        api.postJson("/api/v1/instruments/" + instrument + "/price",
                Map.of("price", new BigDecimal(latest), "asOf", today.minusDays(1).toString()), 200);
        return holdingRepository.findByBrokerAccountIdAndInstrumentId(broker, instrument).orElseThrow().getId();
    }

    private void trade(UUID broker, UUID instrument, String type, String quantity, String price, LocalDate date)
            throws Exception {
        api.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", quantity, "price", price,
                "tradeDate", date.toString()));
    }

    private void prepay(UUID loan, LocalDate date, String amount) throws Exception {
        Map<String, Object> prepayment = new HashMap<>();
        prepayment.put("eventType", "prepayment");
        prepayment.put("effectiveDate", date.toString());
        prepayment.put("amount", amount);
        prepayment.put("adjustmentMode", "reduce_tenure");
        assertEquals(2, api.post("/api/v1/loans/" + loan + "/events", prepayment).getStatus() / 100);
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
                     boolean excluded, String... categoryNames) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), description,
                TransactionSource.manual, type, false, excluded);
        t.setUser(account.getUser());
        for (String name : categoryNames) {
            Category category = categories.computeIfAbsent(name,
                    n -> categoryRepository.save(new Category(n, account.getUser())));
            t.getCategories().add(new TransactionCategory(t, category));
        }
        return transactionRepository.save(t).getId();
    }

    private static List<UUID> rowIds(JsonNode table) {
        List<UUID> ids = new ArrayList<>();
        table.get("rows").forEach(r -> ids.add(UUID.fromString(r.get("id").asText())));
        return ids;
    }

    private static Map<String, JsonNode> byId(JsonNode table) {
        Map<String, JsonNode> rows = new HashMap<>();
        table.get("rows").forEach(r -> rows.put(r.get("id").asText(), r));
        return rows;
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
