package com.financeos.api.reward;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /rewards/report without from/to uses the card's current reward year, the window /rewards/totals reports. */
@SpringBootTest
@AutoConfigureMockMvc
class RewardReportDefaultWindowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private UUID userId;
    private UUID card;

    @BeforeEach
    void setUp() throws Exception {
        String email = "reward-window-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Window Card", "last4", "1111",
                "creditLimit", 100000, "anniversaryDate", "2025-06-15", "financialPosition", "liability", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void defaultWindowEqualsTheTotalsWindow() throws Exception {
        JsonNode mine = null;
        for (JsonNode t : api.getJson("/api/v1/rewards/totals", 200)) {
            if (t.get("accountId").asText().equals(card.toString())) {
                mine = t;
            }
        }
        JsonNode report = api.getJson("/api/v1/rewards/report?accountId=" + card, 200);
        assertEquals(mine.get("from").asText(), report.get("windowFrom").asText());
        assertEquals(mine.get("to").asText(), report.get("windowTo").asText());
        assertEquals(mine.get("calendarYearFallback").asBoolean(), report.get("calendarYearFallback").asBoolean());
        assertTrue(report.get("defaultedWindow").asBoolean());
    }

    @Test
    void defaultReportEqualsTheExplicitReportOverTheSameWindow() throws Exception {
        JsonNode dflt = api.getJson("/api/v1/rewards/report?accountId=" + card, 200);
        JsonNode explicit = api.getJson("/api/v1/rewards/report?accountId=" + card + "&from="
                + dflt.get("windowFrom").asText() + "&to=" + dflt.get("windowTo").asText(), 200);
        assertEquals(explicit.get("summary"), dflt.get("summary"));
        assertEquals(explicit.get("rules"), dflt.get("rules"));
        assertEquals(explicit.get("milestones"), dflt.get("milestones"));
        assertFalse(explicit.get("defaultedWindow").asBoolean());
        assertEquals("2026-01-01", api.getJson("/api/v1/rewards/report?accountId=" + card
                + "&from=2026-01-01&to=2026-01-31", 200).get("windowFrom").asText());
    }

    @Test
    void onlyOneBoundIsRejected() throws Exception {
        assertTrue(api.get("/api/v1/rewards/report?accountId=" + card + "&from=2026-01-01").getStatus() >= 400);
    }

    @Test
    void anotherUsersCardIsRefusedLikeTheExplicitCall() throws Exception {
        String email = "reward-window-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, email);
        UUID otherId = userRepository.findByEmail(email).orElseThrow().getId();
        try {
            int explicit = other.get("/api/v1/rewards/report?accountId=" + card + "&from=2026-01-01&to=2026-12-31")
                    .getStatus();
            assertTrue(explicit >= 400);
            assertEquals(explicit, other.get("/api/v1/rewards/report?accountId=" + card).getStatus());
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherId));
        }
    }
}
