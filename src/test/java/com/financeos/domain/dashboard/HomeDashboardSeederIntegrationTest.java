package com.financeos.domain.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HomeDashboardSeeder against real persistence: the users.home_seeded_at claim
 * ({@link UserRepository#markHomeSeeded}) and the seeded rows commit or roll back together, and a
 * claimed user is never seeded twice.
 *
 * <p>The spy set below is shared verbatim with the other navigation integration tests so they reuse
 * one Spring context; unstubbed spies call the real beans.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HomeDashboardSeederIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private HomeDashboardSeeder seeder;
    @Autowired private JdbcTemplate jdbc;

    @SpyBean private CardBillService cardBillService;
    @SpyBean private LoanService loanService;
    @SpyBean private LendingService lendingService;
    @SpyBean private DashboardService dashboardService;

    private UUID userId;
    private Cookie session;

    @BeforeEach
    void setUp() throws Exception {
        String email = "home-seed-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        String uid = userId.toString();
        jdbc.update("DELETE FROM dashboards WHERE user_id = ?", uid);
        jdbc.update("DELETE FROM reports WHERE user_id = ?", uid);
        jdbc.update("DELETE FROM users WHERE id = ?", uid);
    }

    @Test
    void failedDashboardCreate_rollsBackClaimAndSpendReport_andTheNextCallSeeds() {
        doThrow(new IllegalStateException("dashboard insert failed")).when(dashboardService).create(any());

        UserContext.setCurrentUserId(userId);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> seeder.seedIfNeeded(userId));
        assertEquals("dashboard insert failed", failure.getMessage());

        assertNull(homeSeededAt(), "the claim rolls back with the failed seed");
        assertEquals(0, dashboards());
        assertEquals(0, spendReports(), "the spend report created before the failure rolls back too");

        doCallRealMethod().when(dashboardService).create(any());
        seeder.seedIfNeeded(userId);

        assertNotNull(homeSeededAt());
        assertEquals(1, dashboards());
        assertEquals(1, defaultHomeDashboards());
        assertEquals(1, spendReports());
    }

    @Test
    void twoSequentialCalls_seedExactlyOnce_andKeepTheFirstClaim() {
        UserContext.setCurrentUserId(userId);

        seeder.seedIfNeeded(userId);
        Timestamp firstClaim = homeSeededAt();
        seeder.seedIfNeeded(userId);

        assertNotNull(firstClaim);
        assertEquals(firstClaim, homeSeededAt(), "the second call's claim is a no-op");
        assertEquals(1, dashboards());
        assertEquals(1, defaultHomeDashboards());
        assertEquals(1, spendReports());
        verify(dashboardService, times(1)).create(any());
    }

    @Test
    void http_failedFirstVisit_isRetriedByTheNextVisit() throws Exception {
        doThrow(new IllegalStateException("dashboard insert failed")).when(dashboardService).create(any());
        mockMvc.perform(get("/api/v1/dashboards").cookie(session)).andExpect(status().is5xxServerError());
        assertNull(homeSeededAt());
        assertEquals(0, dashboards());

        doCallRealMethod().when(dashboardService).create(any());
        JsonNode list = objectMapper.readTree(mockMvc.perform(get("/api/v1/dashboards").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(1, list.size());
        assertEquals(HomeDashboardSeeder.HOME_NAME, list.get(0).get("name").asText());
        assertNotNull(homeSeededAt());
    }

    @Test
    void http_repeatedVisits_listExactlyOneHome() throws Exception {
        mockMvc.perform(get("/api/v1/dashboards").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/dashboards/default").cookie(session)).andExpect(status().isOk());
        JsonNode list = objectMapper.readTree(mockMvc.perform(get("/api/v1/dashboards").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(1, list.size());
        assertTrue(list.get(0).get("isDefault").asBoolean());
        assertEquals(1, spendReports());
    }

    // ------------------------------------------------------------------ helpers

    private Timestamp homeSeededAt() {
        return jdbc.queryForObject("SELECT home_seeded_at FROM users WHERE id = ?", Timestamp.class, userId.toString());
    }

    private int dashboards() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dashboards WHERE user_id = ?", Integer.class, userId.toString());
    }

    private int defaultHomeDashboards() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dashboards WHERE user_id = ? AND name = ? AND is_default = TRUE",
                Integer.class, userId.toString(), HomeDashboardSeeder.HOME_NAME);
    }

    private int spendReports() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE user_id = ? AND name = ?",
                Integer.class, userId.toString(), HomeDashboardSeeder.SPEND_REPORT_NAME);
    }

    private Cookie authenticate(String email) throws Exception {
        String password = "homeSeedPass123!";
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email, "password", password, "inviteCode", "test-invite-code"))));
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("FINANCEOS_SESSION");
    }
}
