package com.financeos.api.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * GET /rewards/totals over three cards with several months of transactions, each carrying several tags:
 * the values equal the ones computed before the query change, and the statement count stays bounded.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RewardTotalsQueryCountIntegrationTest {

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
        String email = "reward-totals-qc-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM reward_rule_categories WHERE rule_id IN (SELECT id FROM reward_rules WHERE user_id = ?)", userId.toString());
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private void rule(UUID card, String name, int priority, String percent, List<UUID> categories) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", card.toString());
        body.put("name", name);
        body.put("priority", priority);
        body.put("accrualType", "PERCENT");
        body.put("rewardType", "CASH");
        body.put("percentRate", percent);
        if (!categories.isEmpty()) {
            body.put("categoryIds", categories.stream().map(UUID::toString).toList());
        }
        api.create("/api/v1/reward-rules", body);
    }

    @Test
    void totalsAreUnchangedAndStatementsBounded() throws Exception {
        LocalDate year = LocalDate.of(LocalDate.now().getYear(), 1, 1);
        UUID dining = api.create("/api/v1/tags", Map.of("name", "Dining"));
        UUID fuel = api.create("/api/v1/tags", Map.of("name", "Fuel"));
        UUID travel = api.create("/api/v1/tags", Map.of("name", "Travel"));
        List<List<UUID>> tagSets = List.of(List.of(dining, fuel, travel), List.of(dining, travel), List.of(fuel), List.of());
        List<UUID> cards = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            UUID card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Card " + c,
                    "last4", "50" + c + "0", "creditLimit", 100000, "anniversaryDate", year.minusYears(1).toString(), "financialPosition", "liability",
                    "excludeFromNetAsset", false));
            cards.add(card);
            rule(card, "Base", 10, "1.00", List.of());
            rule(card, "Dining", 100, "5.00", List.of(dining));
        }
        int n = 0;
        for (int c = 0; c < 3; c++) {
            for (int m = 0; m < 4; m++) {
                for (int d = 0; d < 6; d++) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("accountId", cards.get(c).toString());
                    body.put("date", year.plusMonths(m).plusDays(d * 3L).toString());
                    body.put("amount", -(100 + 37 * n));
                    body.put("description", "Spend " + n);
                    body.put("tagIds", tagSets.get(n % tagSets.size()).stream().map(UUID::toString).toList());
                    api.create("/api/v1/transactions", body);
                    n++;
                }
            }
        }

        api.getJson("/api/v1/rewards/totals", 200); // warm up
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        JsonNode result;
        jobWorker.pause();
        try {
            stats.clear();
            result = api.getJson("/api/v1/rewards/totals", 200);
        } finally {
            jobWorker.resume();
        }
        long statements = stats.getPrepareStatementCount();
        Map<String, String> got = new LinkedHashMap<>();
        for (int c = 0; c < 3; c++) {
            for (JsonNode t : result) {
                if (t.get("accountId").asText().equals(cards.get(c).toString())) {
                    got.put("c" + c, t.get("points").decimalValue().stripTrailingZeros().toPlainString() + "/"
                            + t.get("grossValueInr").decimalValue().stripTrailingZeros().toPlainString());
                }
            }
        }
        assertEquals(3, got.size());
        assertEquals(GOLDEN, got.toString());
        assertTrue(statements <= STATEMENT_BOUND, "statements " + statements);
    }

    private static final String GOLDEN = "{c0=0/360.6, c1=0/999.96, c2=0/1639.32}";
    private static final long STATEMENT_BOUND = 14;
}
