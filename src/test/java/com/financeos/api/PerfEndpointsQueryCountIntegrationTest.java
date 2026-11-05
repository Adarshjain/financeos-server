package com.financeos.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
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
 * Query counts of the endpoints the Accounts page and the shell poll (GET /accounts, the net_worth built-in,
 * GET /shell/counts) stay flat as accounts grow, and the transaction search's page-restricted balances equal the
 * whole-ledger running balances.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PerfEndpointsQueryCountIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory emf;
    @Autowired private JobWorker jobWorker;

    private ApiTestClient api;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "perf-qc-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private UUID bank(String name, String last4) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", last4,
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID card(String name, String last4) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", name, "last4", last4,
                "creditLimit", 100000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
    }

    /** A card in a pool: the first card of a pool creates it, the rest join it by id. Returns the account id. */
    private UUID pooledCard(String name, String last4, UUID poolId, String poolName) throws Exception {
        Map<String, Object> pool = poolId == null
                ? Map.of("name", poolName, "creditLimit", 500000)
                : Map.of("id", poolId.toString());
        return api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", name, "last4", last4,
                "anniversaryDate", "2026-01-15", "financialPosition", "liability", "excludeFromNetAsset", false,
                "limitPool", pool));
    }

    private UUID poolOf(UUID account) throws Exception {
        return UUID.fromString(api.getJson("/api/v1/accounts/" + account, 200).get("limitPool").get("id").asText());
    }

    private UUID txn(UUID account, String date, int amount, String description) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", date);
        body.put("amount", amount);
        body.put("description", description);
        return api.create("/api/v1/transactions", body);
    }

    /** Statements run for one call; statistics are global, so the job worker's poll is paused. */
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
        queries(call); // warm up
        return queries(call);
    }

    @Test
    void accountsNetWorthAndShellCountsDoNotGrowWithTheNumberOfAccounts() throws Exception {
        for (int i = 0; i < 3; i++) {
            txn(bank("Bank " + i, "10" + i + "0"), "2026-01-05", -10, "t" + i);
            card("Card " + i, "20" + i + "0");
        }
        UUID poolA = null;
        UUID poolB = null;
        for (int i = 0; i < 3; i++) {
            UUID a = pooledCard("PoolA " + i, "30" + i + "1", poolA, "Pool A");
            if (poolA == null) poolA = poolOf(a);
            UUID b = pooledCard("PoolB " + i, "40" + i + "1", poolB, "Pool B");
            if (poolB == null) poolB = poolOf(b);
        }
        Call accounts = () -> api.getJson("/api/v1/accounts", 200);
        Call netWorth = () -> api.postJson("/api/v1/dashboards/builtins/net_worth/data", Map.of(), 200);
        Call shell = () -> api.getJson("/api/v1/shell/counts", 200);
        long accountsSmall = measure(accounts);
        long netWorthSmall = measure(netWorth);
        long shellSmall = measure(shell);
        System.out.println("QC small accounts=" + accountsSmall + " netWorth=" + netWorthSmall + " shell=" + shellSmall);

        for (int i = 3; i < 9; i++) {
            txn(bank("Bank " + i, "10" + i + "0"), "2026-01-05", -10, "t" + i);
            card("Card " + i, "20" + i + "0");
            pooledCard("PoolA " + i, "31" + i + "1", poolA, "Pool A");
            pooledCard("PoolB " + i, "41" + i + "1", poolB, "Pool B");
        }
        long accountsLarge = measure(accounts);
        long netWorthLarge = measure(netWorth);
        long shellLarge = measure(shell);
        System.out.println("QC large accounts=" + accountsLarge + " netWorth=" + netWorthLarge + " shell=" + shellLarge);

        assertEquals(accountsSmall, accountsLarge, "GET /accounts query count grew with accounts");
        assertEquals(netWorthSmall, netWorthLarge, "net_worth query count grew with accounts");
        assertEquals(shellSmall, shellLarge, "shell/counts query count grew with accounts");
    }

    @Test
    void searchBalancesOfAPageEqualTheWholeLedgerRunningBalances() throws Exception {
        UUID a = bank("Search A", "3001");
        UUID b = bank("Search B", "3002");
        // interleaved dates and a same-day pair, so the date bound and tie-breaks matter
        txn(a, "2026-01-01", -10, "a1");
        txn(b, "2026-01-02", 500, "b1");
        txn(a, "2026-01-03", -20, "a2");
        txn(a, "2026-01-03", 5, "a3");
        txn(b, "2026-01-04", -100, "b2");
        txn(a, "2026-01-05", -30, "a4");
        txn(b, "2026-01-06", -50, "b3");

        Map<String, BigDecimal> expected = Map.of(
                "a1", new BigDecimal("990"), "a2", new BigDecimal("970"), "a3", new BigDecimal("975"),
                "a4", new BigDecimal("945"), "b1", new BigDecimal("1500"), "b2", new BigDecimal("1400"),
                "b3", new BigDecimal("1350"));
        Map<String, BigDecimal> seen = new LinkedHashMap<>();
        for (int page = 0; page < 4; page++) { // 2 rows per page: later pages reach back, earlier ones stop early
            JsonNode result = api.postJson("/api/v1/transactions/search?size=2&page=" + page,
                    Map.of("filters", List.of()), 200);
            assertEquals(7, result.get("totalElements").asInt());
            for (JsonNode row : result.get("content")) {
                seen.put(row.get("description").asText(), row.get("balance").decimalValue());
            }
        }
        assertEquals(expected.size(), seen.size());
        expected.forEach((k, v) -> assertEquals(0, v.compareTo(seen.get(k)), k + " balance " + seen.get(k)));

        // a filter on one account windows only that account and still reports the same balances
        JsonNode onlyB = api.postJson("/api/v1/transactions/search?size=50",
                Map.of("filters", List.of(Map.of("field", "accountId", "operator", "in", "value", List.of(b.toString())))), 200);
        assertEquals(3, onlyB.get("totalElements").asInt());
        for (JsonNode row : onlyB.get("content")) {
            String d = row.get("description").asText();
            assertEquals(0, expected.get(d).compareTo(row.get("balance").decimalValue()), d);
        }
    }

    @Test
    void aShortFirstPageAndAnEmptyResultReportTheRightTotal() throws Exception {
        UUID a = bank("Total A", "4001");
        txn(a, "2026-01-01", -10, "only");
        JsonNode one = api.postJson("/api/v1/transactions/search?size=50", Map.of("filters", List.of()), 200);
        assertEquals(1, one.get("totalElements").asInt());
        JsonNode none = api.postJson("/api/v1/transactions/search?size=50&page=3", Map.of("filters", List.of()), 200);
        assertEquals(0, none.get("content").size());
        JsonNode nothing = api.postJson("/api/v1/transactions/search?size=50",
                Map.of("filters", List.of(), "search", "zzz-no-match"), 200);
        assertEquals(0, nothing.get("totalElements").asInt());
    }
}
