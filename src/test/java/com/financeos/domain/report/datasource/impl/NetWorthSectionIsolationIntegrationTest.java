package com.financeos.domain.report.datasource.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.dashboard.DashboardService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.engine.KpiData;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The net_worth KPI (the Home "Net worth" widget) against real persistence: accounts, active loans
 * and counterparty positions all feed {@code signedValue}, scoped to the caller. This is the
 * baseline the section-failure scenario (reported separately) is measured against.
 *
 * <p>The spy set below is shared verbatim with the other navigation integration tests so they reuse
 * one Spring context; unstubbed spies call the real beans.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetWorthSectionIsolationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ReportDataService reportDataService;
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
        String email = "nw-iso-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "nw-iso-other-" + UUID.randomUUID() + "@example.test";
        otherSession = authenticate(otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        for (UUID id : List.of(userId, otherUserId)) {
            String uid = id.toString();
            jdbc.update("DELETE FROM transactions WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM accounts WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM lendings WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM counterparties WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM loans WHERE user_id = ?", uid);
            jdbc.update("DELETE FROM users WHERE id = ?", uid);
        }
    }

    @Test
    void healthySections_kpiSumsAccountsLendingsAndLoans_forTheCallerOnly() throws Exception {
        seedCallerAndOtherUser();
        BigDecimal outstanding = callerLoanOutstanding();

        KpiData kpi = runAs(userId, () -> (KpiData) reportDataService.runDefinition(
                ReportType.KPI, "net_worth", netWorthDefinition(), null, null));

        // 10,000 wallet + 5,000 lent − the loan's outstanding principal; the other user's rows never count.
        assertEquals(0, new BigDecimal("15000").subtract(outstanding).compareTo(kpi.value()),
                "net worth = " + kpi.value());
    }

    @Test
    void healthySections_builtinWidgetEndpointReturnsTheSameKpi() throws Exception {
        seedCallerAndOtherUser();
        BigDecimal outstanding = callerLoanOutstanding();

        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/dashboards/builtins/net_worth/data")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(0, new BigDecimal("15000").subtract(outstanding).compareTo(body.get("value").decimalValue()));
    }

    // ------------------------------------------------------------------ helpers

    private void seedCallerAndOtherUser() throws Exception {
        walletWithCredit(userId, "10000");
        createLending(session, "Rahul", "5000");
        createLoan(session, "Home loan");
        walletWithCredit(otherUserId, "777777");
        createLending(otherSession, "Other friend", "3333");
        createLoan(otherSession, "Other loan");
    }

    private BigDecimal callerLoanOutstanding() {
        List<LoanResponse> loans = runAs(userId,
                () -> loanService.getLoans(LoanStatus.active, Pageable.unpaged()).getContent());
        assertEquals(1, loans.size());
        return loans.get(0).outstandingPrincipal();
    }

    private ObjectNode netWorthDefinition() {
        ObjectNode def = objectMapper.createObjectNode();
        def.put("measure", "signedValue");
        def.put("aggregation", "sum");
        def.putArray("filters");
        return def;
    }

    private void walletWithCredit(UUID owner, String amount) {
        User user = userRepository.findById(owner).orElseThrow();
        Account account = new Account();
        account.setUser(user);
        account.setName("Wallet");
        account.setType(AccountType.generic);
        account = accountRepository.save(account);
        Transaction credit = new Transaction(account, AppTime.today().minusDays(3), new BigDecimal(amount),
                "Opening", TransactionSource.manual, TransactionType.CREDIT, false, false);
        credit.setUser(user);
        transactionRepository.save(credit);
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

    private void createLending(Cookie cookie, String counterparty, String amount) throws Exception {
        LocalDate today = AppTime.today();
        Map<String, Object> body = new HashMap<>();
        body.put("newCounterpartyName", counterparty);
        body.put("direction", "lent");
        body.put("amount", amount);
        body.put("entryDate", today.minusDays(5).toString());
        mockMvc.perform(post("/api/v1/lendings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .cookie(cookie))
                .andExpect(status().is2xxSuccessful());
    }

    private Cookie authenticate(String email) throws Exception {
        String password = "netWorthIsoPass123!";
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
    void loansSectionFailure_kpiStillSumsAccountsAndLendings_viaReportDataService() throws Exception {
        seedCallerAndOtherUser();
        org.mockito.Mockito.doThrow(new IllegalStateException("loans section broke"))
                .when(loanService).getLoans(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        KpiData kpi = runAs(userId, () -> (KpiData) reportDataService.runDefinition(
                ReportType.KPI, "net_worth", netWorthDefinition(), null, null));

        assertEquals(0, new BigDecimal("15000").compareTo(kpi.value()), "net worth = " + kpi.value());
    }

    @Test
    void lendingsSectionFailure_kpiStillSumsAccountsAndLoans_viaBuiltinWidgetEndpoint() throws Exception {
        seedCallerAndOtherUser();
        BigDecimal outstanding = callerLoanOutstanding();
        org.mockito.Mockito.doThrow(new IllegalStateException("lendings section broke"))
                .when(lendingService).getCounterparties(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        JsonNode body = objectMapper.readTree(mockMvc.perform(post("/api/v1/dashboards/builtins/net_worth/data")
                        .cookie(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(0, new BigDecimal("10000").subtract(outstanding).compareTo(body.get("value").decimalValue()));
    }
}
