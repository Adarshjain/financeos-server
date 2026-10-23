package com.financeos.api.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.JobWorker;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
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

/** GET /counterparties issues a bounded number of queries regardless of page size, and matches the per-person view. */
@SpringBootTest
@AutoConfigureMockMvc
class CounterpartyListQueryCountIntegrationTest {

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
        String email = "cp-qc-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private UUID person(String name) throws Exception {
        return api.create("/api/v1/counterparties", Map.of("name", name));
    }

    private void entry(UUID cp, String dir, String kind, String amount, String date, String expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("counterpartyId", cp.toString());
        body.put("direction", dir);
        body.put("kind", kind);
        body.put("amount", amount);
        body.put("entryDate", date);
        if (expected != null) body.put("expectedReturnDate", expected);
        api.create("/api/v1/lendings", body);
    }

    private long queriesFor(String path) throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        // statistics are global: keep the job worker's poll out of the count
        jobWorker.pause();
        try {
            stats.clear();
            api.getJson(path, 200);
            return stats.getPrepareStatementCount();
        } finally {
            jobWorker.resume();
        }
    }

    @Test
    void queryCountDoesNotGrowWithThePageSize() throws Exception {
        for (int i = 0; i < 3; i++) {
            UUID cp = person("P" + i);
            entry(cp, "lent", "principal", "100", "2026-03-01", "2026-04-01");
        }
        queriesFor("/api/v1/counterparties?size=25"); // warm up
        long small = queriesFor("/api/v1/counterparties?size=25");
        for (int i = 3; i < 20; i++) {
            UUID cp = person("P" + i);
            entry(cp, "borrowed", "principal", "50", "2026-03-01", null);
        }
        long large = queriesFor("/api/v1/counterparties?size=25");
        assertEquals(small, large, "query count grew with the number of people on the page");
        assertTrue(large <= 8, "expected a bounded number of queries, got " + large);
        long outstanding = queriesFor("/api/v1/counterparties?size=25&outstanding=true");
        assertTrue(outstanding <= 10, "outstanding path used " + outstanding + " queries");
    }

    @Test
    void listResponsesEqualThePerPersonResponsesFieldByField() throws Exception {
        person("A None");
        UUID settled = person("B Settled");
        entry(settled, "lent", "principal", "100", "2026-02-01", "2026-03-01");
        entry(settled, "borrowed", "settlement", "100", "2026-02-10", null);
        UUID ties = person("C Ties");
        entry(ties, "lent", "principal", "10", "2026-03-05", "2026-05-01");
        entry(ties, "borrowed", "principal", "4", "2026-03-05", null);
        entry(ties, "lent", "principal", "7", "2026-03-05", "2026-04-01");
        entry(ties, "lent", "principal", "1", "2026-01-01", null);
        UUID owes = person("D Owes");
        entry(owes, "borrowed", "principal", "900", "2026-01-15", "2026-02-15");
        entry(owes, "lent", "settlement", "100", "2026-01-20", null);

        for (String query : List.of("?size=50", "?size=50&outstanding=true", "?size=50&sort=net")) {
            JsonNode content = api.getJson("/api/v1/counterparties" + query, 200).get("content");
            assertTrue(content.size() >= 2, query);
            for (JsonNode row : content) {
                JsonNode single = api.getJson("/api/v1/counterparties/" + row.get("id").asText(), 200);
                assertEquals(single, row, query + " " + row.get("name").asText());
            }
        }
        assertEquals(4, api.getJson("/api/v1/counterparties?size=50", 200).get("content").size());
    }
}
