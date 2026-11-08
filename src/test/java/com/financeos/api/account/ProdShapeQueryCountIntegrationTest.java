package com.financeos.api.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prod-shaped data (the shape that costs prod 45 / 23 / 29 statements on GET /shell/counts, /accounts/{id}, /bills): cards in
 * one shared limit pool and bills paid from a bank account each, with the inbox memo off so the shell poll rebuilds the inbox.
 * The statement counts stay flat when the pool members, the paid bills and their payer accounts triple.
 */
@SpringBootTest(properties = "app.inbox.memo-ttl-seconds=0")
@AutoConfigureMockMvc
class ProdShapeQueryCountIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JobWorker jobWorker;
    @Autowired private StatementPersistenceService statementPersistenceService;

    private ApiTestClient api;
    private User user;
    private final List<UUID> cards = new ArrayList<>();
    private int statementSeq;

    @BeforeEach
    void setUp() throws Exception {
        AppTime.useClock(Clock.fixed(TODAY.atTime(12, 0).atZone(AppTime.zone()).toInstant(), AppTime.zone()));
        String email = "acct-qc-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    private UUID poolId;

    private void addCard(int index) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("type", "credit_card", "name", "QC Card " + index,
                "last4", "7" + index + "11", "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        if (poolId == null) {
            body.put("limitPool", Map.of("name", "QC Pool", "creditLimit", 500000));
        } else {
            body.put("limitPool", Map.of("id", poolId));
        }
        UUID id = api.create("/api/v1/accounts", body);
        if (poolId == null) {
            poolId = UUID.fromString(jdbc.queryForObject(
                    "select limit_pool_id from account_credit_card_details where account_id = ?", String.class, id.toString()));
        }
        cards.add(id);
        api.create("/api/v1/accounts/" + id + "/cardholders",
                Map.of("personName", "Holder " + index, "relationship", "SPOUSE", "last4", "8" + index + "22"));
    }

    /** Pays the card's newest bill from a bank account of its own (bank debit + card credit + payment row). */
    private void payBillFromNewBank(UUID card, int index) throws Exception {
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "QC Payer " + index,
                "last4", "5" + index + "01", "openingBalance", 1000, "financialPosition", "asset",
                "excludeFromNetAsset", false));
        String statementId = jdbc.queryForObject(
                "select id from statements where account_id = ? order by period_end desc fetch first 1 rows only",
                String.class, card.toString());
        api.postJson("/api/v1/bills/" + statementId + "/mark-paid",
                Map.of("payment", Map.of("mode", "NEW", "fromAccountId", bank.toString())), 200);
    }

    private void addStatements(UUID card, int from, int to) {
        Account account = accountRepository.findById(card).orElseThrow();
        for (int i = from; i < to; i++) {
            LocalDate end = TODAY.minusMonths(i).withDayOfMonth(15);
            Map<String, Object> f = new HashMap<>();
            f.put("total_amount_due", new BigDecimal("100"));
            f.put("minimum_amount_due", new BigDecimal("10"));
            f.put("payment_due_date", end.plusDays(18));
            f.put("credit_limit", new BigDecimal("100000"));
            StatementDraft draft = new StatementDraft("credit_card", end.minusDays(30), end, null, BigDecimal.ZERO,
                    "HDFC Bank", "XXXX1111", 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, "heuristic", BigDecimal.ZERO, null,
                    StatementVerdict.AUTO_INGEST, f);
            statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                    "qc-" + statementSeq + ".pdf", "sha-qc-" + (statementSeq++), draft).orElseThrow();
        }
    }

    private void loan(int i) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "QC Loan " + i);
        body.put("loanType", "personal");
        body.put("lender", "Lender");
        body.put("loanAccountNumber", "LN" + i);
        body.put("principal", "100000");
        body.put("annualRatePct", "12");
        body.put("rateType", "fixed");
        body.put("tenureMonths", 12);
        body.put("startDate", "2026-10-25");
        body.put("firstEmiDate", "2026-10-25");
        api.create("/api/v1/loans", body);
    }

    private long queries(Call call) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        jobWorker.pause();
        try {
            stats.clear();
            call.run();
            return stats.getPrepareStatementCount();
        } finally {
            jobWorker.resume();
        }
    }

    private interface Call {
        void run() throws Exception;
    }

    private long measure(Call call) throws Exception {
        queries(call);
        return queries(call);
    }

    @Test
    void routeStatementCountsDoNotGrowWithPoolMembersAndPaidBills() throws Exception {
        api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "QC Bank", "last4", "6001",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        loan(0);
        for (int i = 0; i < 2; i++) addCard(i);
        for (UUID c : cards) addStatements(c, 0, 6);
        for (int i = 0; i < cards.size(); i++) payBillFromNewBank(cards.get(i), i);
        UUID first = cards.get(0);

        Map<String, Call> routes = new LinkedHashMap<>();
        routes.put("shell", () -> api.getJson("/api/v1/shell/counts", 200));
        routes.put("account", () -> api.getJson("/api/v1/accounts/" + first, 200));
        routes.put("cardSummary", () -> api.getJson("/api/v1/accounts/" + first + "/card-summary", 200));
        routes.put("holderStats", () -> api.getJson("/api/v1/accounts/" + first + "/cardholders/stats", 200));
        routes.put("bills", () -> api.getJson("/api/v1/bills", 200));

        Map<String, Long> small = new LinkedHashMap<>();
        for (var e : routes.entrySet()) small.put(e.getKey(), measure(e.getValue()));

        for (UUID c : cards) addStatements(c, 6, 12);
        for (int i = 2; i < 6; i++) {
            addCard(i);
            addStatements(cards.get(i), 0, 12);
            payBillFromNewBank(cards.get(i), i);
        }
        for (int i = 0; i < 3; i++) api.create("/api/v1/accounts/" + first + "/cardholders",
                Map.of("personName", "Extra " + i, "relationship", "CHILD", "last4", "9" + i + "33"));
        loan(1);

        Map<String, Long> large = new LinkedHashMap<>();
        for (var e : routes.entrySet()) large.put(e.getKey(), measure(e.getValue()));
        System.out.println("QC small=" + small + " large=" + large);

        for (String k : routes.keySet()) {
            assertEquals(small.get(k), large.get(k), k + " query count grew with data: " + small + " -> " + large);
            assertTrue(large.get(k) <= BOUNDS.get(k), k + " used " + large.get(k) + " statements");
        }

        // The response is unchanged: every card's newest bill lists its one payment, from its own payer account.
        JsonNode bills = api.getJson("/api/v1/bills", 200);
        int checked = 0;
        for (int i = 0; i < cards.size(); i++) {
            for (JsonNode bill : bills) {
                if (!cards.get(i).toString().equals(bill.get("accountId").asText())) continue;
                JsonNode payments = bill.get("payments");
                assertEquals(1, payments.size());
                assertEquals("QC Payer " + i, payments.get(0).get("bankAccountName").asText());
                assertEquals(0, new BigDecimal("100").compareTo(payments.get(0).get("amount").decimalValue()));
                checked++;
            }
        }
        assertEquals(cards.size(), checked);
        JsonNode account = api.getJson("/api/v1/accounts/" + first, 200);
        assertEquals(cards.size(), account.get("limitPool").get("memberCount").asInt());
        assertEquals(0, new BigDecimal("500000").compareTo(account.get("limitPool").get("creditLimit").decimalValue()));
    }

    private static final Map<String, Long> BOUNDS = Map.of(
            "shell", 45L, "account", 12L, "cardSummary", 12L, "holderStats", 12L, "bills", 15L);
}
