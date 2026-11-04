package com.financeos.api.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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

/**
 * The emergency-fund outflow of a past month and the report filter {@code isExcludedFromOutflow = false} share one
 * rule: a card bill excluded only by the category default counts; user-excluded, uncategorised-excluded and
 * other-category excluded rows stay out. Distinct amounts identify which rows counted.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmergencyFundOutflowExclusionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private UUID userId;
    private YearMonth month;

    @BeforeEach
    void setUp() throws Exception {
        month = YearMonth.from(AppTime.today()).minusMonths(1);
        String email = "ef-outflow-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "EF Bank", "last4", "7777",
                "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Account account = accountRepository.findById(bank).orElseThrow();

        seed(account, 1, "1", false, false, null);                // (a) plain debit: counts
        seed(account, 2, "20", true, false, "card_bill");         // (b) card bill excluded by default: counts
        seed(account, 3, "300", true, true, "card_bill");         // (c) card bill the user excluded: out
        seed(account, 4, "4000", true, false, null);              // (d) excluded, uncategorised: out
        seed(account, 5, "50000", true, false, "self_transfer");  // (e) excluded, another category: out
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void emergencyFundOutflowCountsOnlyThePlainDebitAndTheDefaultExcludedCardBill() throws Exception {
        JsonNode response = api.getJson("/api/v1/insights/emergency-fund", 200);
        BigDecimal outflow = null;
        for (JsonNode m : response.get("months")) {
            if (month.toString().equals(m.get("month").asText())) {
                outflow = m.get("outflow").decimalValue();
            }
        }
        assertEquals(0, new BigDecimal("21").compareTo(outflow), "1 + 20 only, got " + outflow);
    }

    @Test
    void theReportFilterIsExcludedFromOutflowFalseSumsTheSameRows() throws Exception {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("measure", "spend");
        kpi.put("aggregation", "sum");
        kpi.put("filters", List.of(
                Map.of("field", "type", "operator", "is", "value", "DEBIT"),
                Map.of("field", "isExcludedFromOutflow", "operator", "is", "value", false)));
        UUID reportId = api.create("/api/v1/reports", Map.of("name", "Outflow", "type", "KPI",
                "datasource", "transactions", "definition", kpi));

        JsonNode result = api.postJson("/api/v1/reports/" + reportId + "/data", null, 200);

        assertEquals(0, new BigDecimal("21").compareTo(result.get("value").decimalValue()),
                "report sum " + result.get("value"));
    }

    private void seed(Account account, int day, String amount, boolean excluded, boolean byUser, String categoryKey) {
        LocalDate date = month.atDay(day);
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "ef-" + amount,
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setUser(account.getUser());
        t = transactionRepository.saveAndFlush(t);
        jdbc.update("UPDATE transactions SET is_excluded = ?, exclude_set_by_user = ?, category_key = ? WHERE id = ?",
                excluded ? 1 : 0, byUser ? 1 : 0, categoryKey, t.getId().toString());
    }
}
