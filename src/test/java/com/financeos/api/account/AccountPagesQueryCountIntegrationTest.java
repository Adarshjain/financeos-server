package com.financeos.api.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Statement counts of the account pages and the shell poll (GET /shell/counts, /accounts/{id}, card-summary,
 * cardholders/stats, /bills) stay flat when the number of cards, statements and cardholders doubles.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountPagesQueryCountIntegrationTest {

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

    private void addCard(int index) throws Exception {
        UUID id = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "QC Card " + index,
                "last4", "7" + index + "11", "creditLimit", 100000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        cards.add(id);
        api.create("/api/v1/accounts/" + id + "/cardholders",
                Map.of("personName", "Holder " + index, "relationship", "SPOUSE", "last4", "8" + index + "22"));
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
    void accountPagesAndShellCountsDoNotGrowWithCardsStatementsAndCardholders() throws Exception {
        api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "QC Bank", "last4", "6001",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        loan(0);
        for (int i = 0; i < 3; i++) addCard(i);
        for (UUID c : cards) addStatements(c, 0, 6);
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
        for (int i = 3; i < 6; i++) {
            addCard(i);
            addStatements(cards.get(i), 0, 12);
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
    }

    private static final Map<String, Long> BOUNDS = Map.of(
            "shell", 12L, "account", 12L, "cardSummary", 12L, "holderStats", 12L, "bills", 15L);
}
