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
 * ObligationsService against real persistence: with every section healthy, the EMI and lending
 * rows of the caller (and only the caller) come back through the transactional proxy and the HTTP
 * endpoint. This is the baseline the section-failure scenario (reported separately) is measured
 * against.
 *
 * <p>The spy set below is shared verbatim with the other navigation integration tests so they reuse
 * one Spring context; unstubbed spies call the real beans.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObligationsSectionIsolationIntegrationTest {

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
        String email = "oblig-iso-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "oblig-iso-other-" + UUID.randomUUID() + "@example.test";
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
    void healthySections_returnOnlyTheCallersEmiAndLendingRows_viaServiceProxy() throws Exception {
        createLoan(session, "Home loan");
        createLending(session, "Rahul");
        createLoan(otherSession, "Other user's loan");
        createLending(otherSession, "Other user's friend");

        ObligationsResponse response = runAs(userId, () -> obligationsService.upcoming(userId, 3, null));

        List<ObligationItemDto> emis = ofType(response, ObligationsService.KIND_EMI);
        List<ObligationItemDto> lendings = ofType(response, ObligationsService.KIND_LENDING_DUE);
        assertFalse(emis.isEmpty(), "the active loan's installments inside the window are listed");
        assertTrue(emis.stream().allMatch(i -> "Home loan".equals(i.loanName())));
        assertEquals(1, lendings.size());
        assertEquals("Rahul owes you", lendings.get(0).title());
    }

    @Test
    void healthySections_returnEmiAndLendingRows_viaHttpEndpoint() throws Exception {
        createLoan(session, "Home loan");
        createLending(session, "Rahul");

        JsonNode items = objectMapper.readTree(mockMvc.perform(get("/api/v1/obligations/upcoming").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("items");

        Set<String> types = new java.util.HashSet<>();
        items.forEach(i -> types.add(i.get("type").asText()));
        assertTrue(types.contains(ObligationsService.KIND_EMI));
        assertTrue(types.contains(ObligationsService.KIND_LENDING_DUE));
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

    // Regression: a failing section degrades to no rows instead of failing the whole read.
    @Test
    void cardBillSectionFailure_stillReturnsEmiAndLendingRows_viaServiceProxy() throws Exception {
        createLoan(session, "Home loan");
        createLending(session, "Rahul");
        org.mockito.Mockito.doThrow(new IllegalStateException("bill section broke"))
                .when(cardBillService).listBills(org.mockito.ArgumentMatchers.any());

        ObligationsResponse response = runAs(userId, () -> obligationsService.upcoming(userId, 3, null));

        assertFalse(ofType(response, ObligationsService.KIND_EMI).isEmpty());
        assertEquals(1, ofType(response, ObligationsService.KIND_LENDING_DUE).size());
        assertTrue(ofType(response, ObligationsService.KIND_CARD_BILL).isEmpty());
    }

    @Test
    void cardBillSectionFailure_stillReturnsEmiAndLendingRows_viaHttpEndpoint() throws Exception {
        createLoan(session, "Home loan");
        createLending(session, "Rahul");
        org.mockito.Mockito.doThrow(new IllegalStateException("bill section broke"))
                .when(cardBillService).listBills(org.mockito.ArgumentMatchers.any());

        JsonNode items = objectMapper.readTree(mockMvc.perform(get("/api/v1/obligations/upcoming").cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("items");

        Set<String> types = new java.util.HashSet<>();
        items.forEach(i -> types.add(i.get("type").asText()));
        assertEquals(Set.of(ObligationsService.KIND_EMI, ObligationsService.KIND_LENDING_DUE), types);
    }
}
