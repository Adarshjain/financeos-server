package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import jakarta.persistence.EntityManagerFactory;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /reports names the current user's dashboards that use each report. */
@SpringBootTest
@AutoConfigureMockMvc
class ReportListDashboardsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory emf;

    private ApiTestClient api;
    private ApiTestClient other;
    private UUID userId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "rep-dash-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "rep-dash-other-" + UUID.randomUUID() + "@example.test";
        other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    @Test
    void reportOnNoDashboard_hasAnEmptyList() throws Exception {
        UUID report = report(api, "Lonely");

        JsonNode entry = entry(report);

        assertTrue(entry.get("dashboards").isArray());
        assertEquals(0, entry.get("dashboards").size());
    }

    @Test
    void reportOnTwoDashboards_listsBothOrderedByName() throws Exception {
        UUID report = report(api, "Shared");
        UUID zeta = dashboard(api, "Zeta", report);
        UUID alpha = dashboard(api, "alpha", report);

        JsonNode dashboards = entry(report).get("dashboards");

        assertEquals(2, dashboards.size());
        assertEquals(alpha.toString(), dashboards.get(0).get("id").asText());
        assertEquals("alpha", dashboards.get(0).get("name").asText());
        assertEquals(zeta.toString(), dashboards.get(1).get("id").asText());
        assertEquals("Zeta", dashboards.get(1).get("name").asText());
    }

    @Test
    void reportUsedTwiceOnOneDashboard_listsThatDashboardOnce() throws Exception {
        UUID report = report(api, "Twice");
        UUID dash = dashboard(api, "Doubled", report, report);

        JsonNode dashboards = entry(report).get("dashboards");

        assertEquals(1, dashboards.size());
        assertEquals(dash.toString(), dashboards.get(0).get("id").asText());
    }

    @Test
    void anotherUsersDashboardsNeverLeak() throws Exception {
        UUID mine = report(api, "Mine");
        UUID theirs = report(other, "Theirs");
        dashboard(other, "Their board", theirs);
        UUID myBoard = dashboard(api, "My board", mine);

        JsonNode list = api.getJson("/api/v1/reports", 200);

        assertEquals(1, list.size());
        JsonNode dashboards = list.get(0).get("dashboards");
        assertEquals(1, dashboards.size());
        assertEquals(myBoard.toString(), dashboards.get(0).get("id").asText());
        JsonNode theirList = other.getJson("/api/v1/reports", 200);
        assertEquals("Their board", theirList.get(0).get("dashboards").get(0).get("name").asText());
    }

    @Test
    void queryCountStaysConstantAsReportsGrow() throws Exception {
        UUID first = report(api, "R0");
        dashboard(api, "D0", first);
        api.getJson("/api/v1/reports", 200); // warm up
        long small = queries();

        for (int i = 1; i <= 5; i++) {
            UUID r = report(api, "R" + i);
            dashboard(api, "D" + i, r);
        }
        long large = queries();

        assertEquals(small, large, "list query count must not grow with the number of reports/dashboards");
    }

    // ------------------------------------------------------------------ helpers

    /** Statistics are factory-wide, so a background job can add statements to one run; keep the lowest of three. */
    private long queries() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            stats.clear();
            api.getJson("/api/v1/reports", 200);
            min = Math.min(min, stats.getPrepareStatementCount());
        }
        return min;
    }

    private JsonNode entry(UUID reportId) throws Exception {
        for (JsonNode r : api.getJson("/api/v1/reports", 200)) {
            if (reportId.toString().equals(r.get("id").asText())) {
                return r;
            }
        }
        throw new AssertionError("report not listed");
    }

    private static UUID report(ApiTestClient client, String name) throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "amount");
        def.put("aggregation", "sum");
        def.put("filters", List.of());
        return client.create("/api/v1/reports", Map.of("name", name, "type", "KPI",
                "datasource", "transactions", "definition", def));
    }

    private static UUID dashboard(ApiTestClient client, String name, UUID... reports) throws Exception {
        List<Map<String, Object>> widgets = new ArrayList<>();
        int y = 0;
        for (UUID r : reports) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("id", UUID.randomUUID().toString());
            w.put("reportId", r.toString());
            w.put("layout", Map.of("x", 0, "y", y, "w", 6, "h", 4));
            widgets.add(w);
            y += 4;
        }
        return client.create("/api/v1/dashboards", Map.of("name", name, "widgets", widgets));
    }
}
