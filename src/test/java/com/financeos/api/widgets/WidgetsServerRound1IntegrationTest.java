package com.financeos.api.widgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.price.PriceRefreshService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Against real persistence (H2): live card utilisation through the batch balance SQL and the
 * single-account path, the bill digest and card summary; the instrument asset-class override;
 * and the day change from the latest-two-closes window query.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WidgetsServerRound1IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementRepository statementRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private InstrumentPriceRepository priceRepository;
    @Autowired private PriceRefreshService priceRefreshService;

    private ApiTestClient api;
    private UUID userId;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String email = "widgets-r1-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    /**
     * A card; a null {@code limit} zeroes the card's own limit after creating it (the column is
     * NOT NULL and the API requires one, so a zero limit is how "no limit of its own" looks).
     */
    private UUID card(String name, Integer limit) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "credit_card", "name", name,
                "last4", String.valueOf(1000 + (int) (Math.random() * 8999)), "creditLimit", limit == null ? 1 : limit,
                "anniversaryDate", today.toString(), "financialPosition", "liability", "excludeFromNetAsset", false));
        UUID id = api.create("/api/v1/accounts", body);
        if (limit == null) {
            jdbc.update("UPDATE account_credit_card_details SET credit_limit = 0 WHERE account_id = ?", id.toString());
        }
        return id;
    }

    private void statement(UUID accountId, LocalDate periodEnd, String closing, String limit, StatementVerdict verdict) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement s = new Statement();
        s.setUser(account.getUser());
        s.setAccount(account);
        s.setSource(StatementSource.file_upload);
        s.setStatementType("credit_card");
        s.setPeriodStart(periodEnd.minusMonths(1).plusDays(1));
        s.setPeriodEnd(periodEnd);
        s.setClosingBalance(new BigDecimal(closing));
        s.setVerdict(verdict);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(account.getUser());
        d.setTotalAmountDue(new BigDecimal(closing));
        d.setPaymentDueDate(periodEnd.plusDays(18));
        d.setCreditLimit(limit == null ? null : new BigDecimal(limit));
        s.setCreditCardDetails(d);
        statementRepository.save(s);
    }

    private void txn(UUID accountId, LocalDate date, String amount, TransactionType type) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "Spend", TransactionSource.manual, type,
                false, false);
        t.setUser(account.getUser());
        transactionRepository.save(t);
    }

    private JsonNode listed(UUID accountId) throws Exception {
        for (JsonNode a : api.getJson("/api/v1/accounts", 200)) {
            if (a.get("id").asText().equals(accountId.toString())) {
                return a;
            }
        }
        throw new AssertionError("account " + accountId + " not listed");
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual != null && actual.isNumber(), "expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    // ------------------------------------------------------------------ utilisation

    @Test
    void utilisationIsLiveOnTheListTheAccountTheCardSummaryAndTheBill() throws Exception {
        // No limit of its own: the latest non-rejected statement with a limit (80,000) is the fallback.
        UUID fallback = card("Fallback Card", null);
        LocalDate anchor = today.minusDays(12);
        statement(fallback, anchor.minusMonths(1), "3000", "50000", StatementVerdict.AUTO_INGEST);
        statement(fallback, anchor, "8000", "80000", StatementVerdict.AUTO_INGEST);
        statement(fallback, anchor.plusDays(5), "1", "10000", StatementVerdict.REJECTED);
        txn(fallback, anchor.plusDays(2), "2000", TransactionType.DEBIT);   // owes 8,000 + 2,000

        UUID own = card("Own Limit Card", 200000);
        txn(own, today.minusDays(3), "50000", TransactionType.DEBIT);

        UUID inCredit = card("In Credit Card", 100000);
        txn(inCredit, today.minusDays(3), "500", TransactionType.CREDIT);

        assertDecimal("12.5", listed(fallback).get("utilizationPct"));
        assertDecimal("25.0", listed(own).get("utilizationPct"));
        assertDecimal("0.0", listed(inCredit).get("utilizationPct"));

        assertDecimal("12.5", api.getJson("/api/v1/accounts/" + fallback, 200).get("utilizationPct"));
        assertDecimal("25.0", api.getJson("/api/v1/accounts/" + own, 200).get("utilizationPct"));

        JsonNode summary = api.getJson("/api/v1/accounts/" + fallback + "/card-summary", 200);
        assertDecimal("12.5", summary.get("utilizationPct"));
        assertDecimal("80000", summary.get("creditLimit"));

        JsonNode bill = api.getJson("/api/v1/bills?accountId=" + fallback, 200);
        JsonNode digest = null;
        for (JsonNode b : bill) {
            if (b.get("accountId").asText().equals(fallback.toString())) {
                digest = b.get("digest");
            }
        }
        assertTrue(digest != null, "the card's bill is listed: " + bill);
        assertDecimal("12.5", digest.get("utilizationPct"));
    }

    @Test
    void aCardWithNoLimitAnywhereHasNoUtilisation() throws Exception {
        UUID noLimit = card("No Limit Card", null);
        txn(noLimit, today.minusDays(1), "100", TransactionType.DEBIT);

        assertTrue(listed(noLimit).get("utilizationPct").isNull());
    }

    // ------------------------------------------------------------------ investments

    private UUID stockWithTwoCloses() throws Exception {
        UUID broker = api.create("/api/v1/accounts", Map.of("type", "broker", "name", "W Broker", "provider", "Zerodha",
                "clientId", "CL1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        String symbol = "WR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID instrument = api.create("/api/v1/instruments", Map.of("type", "stock", "name", "Widget Ltd",
                "symbol", symbol, "exchange", "NSE"));
        instrumentIds.add(instrument);
        api.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", "buy", "quantity", "10", "price", "90",
                "tradeDate", today.minusDays(30).toString()));
        api.postJson("/api/v1/instruments/" + instrument + "/price",
                Map.of("price", 100, "asOf", today.minusDays(4).toString()), 200);
        api.postJson("/api/v1/instruments/" + instrument + "/price",
                Map.of("price", 95, "asOf", today.minusDays(3).toString()), 200);
        api.postJson("/api/v1/instruments/" + instrument + "/price",
                Map.of("price", 110, "asOf", today.minusDays(1).toString()), 200);
        return instrument;
    }

    private JsonNode position(UUID instrument) throws Exception {
        for (JsonNode p : api.getJson("/api/v1/investments/positions", 200).get("positions")) {
            if (p.get("instrument").get("id").asText().equals(instrument.toString())) {
                return p;
            }
        }
        throw new AssertionError("no position for " + instrument);
    }

    @Test
    void positionsAndSummaryCarryTheDayChangeFromTheLatestTwoCloses() throws Exception {
        UUID instrument = stockWithTwoCloses();

        JsonNode p = position(instrument);
        assertDecimal("95", p.get("previousClose"));
        assertEquals(today.minusDays(3).toString(), p.get("previousCloseAsOf").asText());
        assertDecimal("150", p.get("dayChange"));
        assertDecimal("15.79", p.get("dayChangePct"));
        assertEquals("EQUITY", p.get("assetClass").asText());
        assertEquals("EQUITY_ORIENTED", p.get("taxClass").asText());

        JsonNode summary = api.getJson("/api/v1/investments/summary", 200);
        assertDecimal("150", summary.get("dayChange"));
        assertDecimal("15.79", summary.get("dayChangePct"));
        assertEquals(today.minusDays(1).toString(), summary.get("priceAsOf").asText());
        assertEquals(today.minusDays(3).toString(), summary.get("previousPriceAsOf").asText());

        // The prices above are this user's own manual prices: only their view has them.
        List<Object[]> rows = priceRepository.findLatestTwoCloses(List.of(instrument.toString()), userId.toString());
        assertEquals(2, rows.size(), "the window query returns only the latest two closes");
        assertEquals(0, priceRepository.findLatestTwoCloses(List.of(instrument.toString()),
                com.financeos.domain.instrument.PricePrecedence.NO_USER).size(), "no feed price exists");
    }

    private JsonNode patch(UUID instrument, String json) throws Exception {
        return api.patchJson("/api/v1/instruments/" + instrument, json, 200);
    }

    @Test
    void anAssetClassOverrideSticksAndResetsToTheRules() throws Exception {
        UUID instrument = stockWithTwoCloses();
        assertEquals("RULE", api.getJson("/api/v1/instruments/" + instrument, 200).get("assetClassSource").asText(),
                "creating a stock classifies it");

        JsonNode pinned = patch(instrument, "{\"assetClass\":\"GOLD\"}");
        assertEquals("GOLD", pinned.get("assetClass").asText());
        assertEquals("MANUAL", pinned.get("assetClassSource").asText());
        assertEquals("EQUITY_ORIENTED", pinned.get("taxClass").asText(), "listed shares stay equity for tax");
        assertEquals("GOLD", position(instrument).get("assetClass").asText());

        // A refresh never overwrites a manual class.
        priceRefreshService.refresh(java.util.Optional.of(instrument));
        assertEquals("GOLD", api.getJson("/api/v1/instruments/" + instrument, 200).get("assetClass").asText());

        JsonNode reset = patch(instrument, "{\"assetClass\":null}");
        assertEquals("EQUITY", reset.get("assetClass").asText());
        assertEquals("RULE", reset.get("assetClassSource").asText());
    }
}
