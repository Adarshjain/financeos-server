package com.financeos.domain.obligations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.obligations.dto.ObligationItemDto;
import com.financeos.api.obligations.dto.ObligationsResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.dashboard.DashboardService;
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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression: with a single-connection pool, upcoming() must not hold a connection of its own while
 * its sections take theirs, or every section times out and the rows silently go missing.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=1000"})
@AutoConfigureMockMvc
class ObligationsUpcomingPoolIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private ObligationsService obligationsService;
    @Autowired private JdbcTemplate jdbc;

    @SpyBean private CardBillService cardBillService;
    @SpyBean private LoanService loanService;
    @SpyBean private LendingService lendingService;
    @SpyBean private DashboardService dashboardService;

    private UUID userId;
    private Cookie session;
    private UUID otherUserId;
    private Cookie otherSession;

    @BeforeEach
    void setUp() throws Exception {
        String email = "oblig-pool-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "oblig-pool-other-" + UUID.randomUUID() + "@example.test";
        otherSession = authenticate(otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        for (UUID id : List.of(userId, otherUserId)) {
            String uid = id.toString();
            jdbc.update("DELETE FROM lendings WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM counterparties WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM loans WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM users WHERE id = ?", uid);
        }
    }

    @Test
    void singleConnectionPool_stillReturnsEmiAndLendingRows_viaServiceProxy() throws Exception {
        createLoan(session, "Home loan");
        createLending(session, "Rahul");

        ObligationsResponse response = runAs(userId, () -> obligationsService.upcoming(userId, 3, null));

        assertFalse(ofType(response, ObligationsService.KIND_EMI).isEmpty());
        assertEquals(1, ofType(response, ObligationsService.KIND_LENDING_DUE).size());
    }

    // ------------------------------------------------------------------ helpers

    private static List<ObligationItemDto> ofType(ObligationsResponse response, String type) {
        return response.items().stream().filter(i -> type.equals(i.type())).toList();
    }

    private static <T> T runAs(UUID user, java.util.function.Supplier<T> call) {
        UserContext.setCurrentUserId(user);
        try {
            return call.get();
        } finally {
            UserContext.clear();
        }
    }

    private void createLoan(Cookie cookie, String name) throws Exception {
        LocalDate today = AppTime.today();
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("loanType", "home");
        body.put("lender", "HDFC");
        body.put("principal", "120000");
        body.put("annualRatePct", "9.5");
        body.put("rateType", "fixed");
        body.put("tenureMonths", 24);
        body.put("startDate", today.minusDays(20).toString());
        body.put("firstEmiDate", today.plusDays(10).toString());
        mockMvc.perform(post("/api/v1/loans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .cookie(cookie))
                .andExpect(status().is2xxSuccessful());
    }

    private void createLending(Cookie cookie, String counterparty) throws Exception {
        LocalDate today = AppTime.today();
        Map<String, Object> body = new HashMap<>();
        body.put("newCounterpartyName", counterparty);
        body.put("direction", "lent");
        body.put("amount", "5000");
        body.put("entryDate", today.minusDays(5).toString());
        body.put("expectedReturnDate", today.plusDays(20).toString());
        mockMvc.perform(post("/api/v1/lendings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .cookie(cookie))
                .andExpect(status().is2xxSuccessful());
    }

    private Cookie authenticate(String email) throws Exception {
        String password = "obligIsoPass123!";
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
