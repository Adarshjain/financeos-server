package com.financeos.api.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import com.financeos.core.time.AppTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** GET /loans?q=, /loans/summary totals and /loans/match-suggestions against real persistence. */
@SpringBootTest
@AutoConfigureMockMvc
class LoanSummaryAndSearchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private UUID userId;
    private UUID otherId;
    private UUID homeLoanId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "loan-sum-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "loan-sum-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();

        // Fully in the past: all 12 installments overdue, due on the 5th.
        homeLoanId = loan(api, "Home Loan", "Acme Bank", "AC-100", "120000", "2020-01-05", 12);
        // Future: EMIs on the 10th.
        loan(api, "Car Loan", "Zed Finance", "ZF_77", "60000", "2099-04-10", 6);
        loan(other, "Home Loan", "Acme Bank", "AC-100", "999999", "2020-01-07", 12);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private static UUID loan(ApiTestClient client, String name, String lender, String acct, String principal,
            String firstEmi, int tenure) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("loanType", "personal");
        body.put("lender", lender);
        body.put("loanAccountNumber", acct);
        body.put("principal", principal);
        body.put("annualRatePct", "12");
        body.put("rateType", "fixed");
        body.put("tenureMonths", tenure);
        body.put("startDate", firstEmi);
        body.put("firstEmiDate", firstEmi);
        return client.create("/api/v1/loans", body);
    }

    private List<String> names(String query) throws Exception {
        List<String> names = new ArrayList<>();
        api.getJson("/api/v1/loans" + query, 200).get("content").forEach(n -> names.add(n.get("name").asText()));
        return names;
    }

    @Test
    void searchMatchesNameLenderAndAccountNumberCaseInsensitively() throws Exception {
        assertEquals(List.of("Home Loan"), names("?q=HOME"));
        assertEquals(List.of("Car Loan"), names("?q=zed"));
        assertEquals(List.of("Home Loan"), names("?q=ac-1"));
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        assertEquals(List.of("Car Loan"), names("?q=f_77"));
        assertTrue(names("?q=%25").isEmpty());
        assertTrue(names("?q=Z_D").isEmpty());
    }

    @Test
    void blankSearchListsEverythingAndCombinesWithStatus() throws Exception {
        assertEquals(2, names("?q=").size());
        assertEquals(2, names("?status=active&q=loan").size());
        assertTrue(names("?status=closed&q=loan").isEmpty());
    }

    @Test
    void searchIsScopedToTheCaller() throws Exception {
        assertEquals(1, names("?q=home").size());
    }

    @Test
    void searchPagesAfterTheFilter() throws Exception {
        JsonNode page = api.getJson("/api/v1/loans?q=loan&size=1&page=1", 200);
        assertEquals(2, page.get("totalElements").asInt());
        assertEquals(1, page.get("content").size());
    }

    @Test
    void summaryTotalsSpanAllOfTheCallersLoans() throws Exception {
        JsonNode s = api.getJson("/api/v1/loans/summary", 200);

        assertEquals(180000, s.get("borrowedPrincipal").asDouble(), 0.001);
        assertEquals(2, s.get("activeLoanCount").asInt());
        assertEquals(2, s.get("emiDueDays").size());
        assertEquals(5, s.get("emiDueDays").get(0).asInt());
        assertEquals(10, s.get("emiDueDays").get(1).asInt());
        assertEquals(12, s.get("overdueCount").asInt());
        assertTrue(s.get("overdueAmount").asDouble() > 120000);
        assertTrue(s.get("monthlyEmiTotal").asDouble() > 0);
        assertEquals(0, s.get("interestPaidThisFy").asDouble(), 0.001);
        assertTrue(s.get("fyStart").asText().endsWith("-04-01"));
        assertEquals(0, s.get("closedLoanCount").asInt());
    }

    @Test
    void twelveMonthLoanHasExactlyTwelveScheduleRows() throws Exception {
        JsonNode rows = api.getJson("/api/v1/loans/" + homeLoanId + "/schedule", 200).get("installments");
        assertEquals(12, rows.size());
        assertEquals(0, rows.get(11).get("closingBalance").asDouble(), 0.0001);
    }

    @Test
    void allLoansMatchSuggestionsResolvesBesideLoanIdAndIsEmptyWithoutMatches() throws Exception {
        JsonNode r = api.getJson("/api/v1/loans/match-suggestions", 200);

        assertTrue(r.isArray());
        assertEquals(0, r.size());
    }

    @Test
    void allLoansMatchSuggestionsListsALoanWithAnEmiSizedDebit() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Match Loan");
        body.put("loanType", "personal");
        body.put("lender", "Bank");
        body.put("principal", "100000");
        body.put("annualRatePct", "12");
        body.put("rateType", "fixed");
        body.put("tenureMonths", 24);
        body.put("startDate", AppTime.today().minusDays(10).toString());
        body.put("firstEmiDate", AppTime.today().toString());
        body.put("emiAmount", "5000");
        UUID matchLoan = api.create("/api/v1/loans", body);
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Match Bank", "last4", "4321",
                "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Map<String, Object> near = new LinkedHashMap<>();
        near.put("accountId", bank.toString());
        near.put("date", AppTime.today().toString());
        near.put("amount", -4910);
        near.put("description", "EMI debit");
        UUID nearTxn = api.create("/api/v1/transactions", near);

        JsonNode r = api.getJson("/api/v1/loans/match-suggestions", 200);

        assertEquals(1, r.size());
        assertEquals(matchLoan.toString(), r.get(0).get("loanId").asText());
        assertEquals("Match Loan", r.get(0).get("loanName").asText());
        boolean found = false;
        for (JsonNode sg : r.get(0).get("suggestions")) {
            for (JsonNode c : sg.get("candidates")) {
                found |= nearTxn.toString().equals(c.get("id").asText());
            }
        }
        assertTrue(found);
    }
}
