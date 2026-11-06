package com.financeos.api.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A foreign charge waiting for its statement (amount NULL, original_amount / original_currency set) on real rows: it
 * shows with no rupee amount, never counts in the account balance, a breakdown report or a KPI and its CSV export, and
 * breaks none of them; the user may leave its amount unknown on edit or set it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForeignChargeNullAmountIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private UUID userId;
    private Account bank;
    private LocalDate today;
    private UUID rupee;
    private UUID foreign;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String email = "foreign-null-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        UUID id = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Fx Bank", "last4", "4321",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        bank = accountRepository.findById(id).orElseThrow();
        rupee = txn(today.withDayOfMonth(1), new BigDecimal("100.00"), null, null, "SWIGGY");
        foreign = txn(today.withDayOfMonth(1), null, new BigDecimal("20.00"), "USD", "GITHUB");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void theAccountBalanceLeavesTheForeignChargeOut() throws Exception {
        assertDecimal("900", api.getJson("/api/v1/accounts/" + bank.getId(), 200).get("balance"));
    }

    @Test
    void theListShowsItWithNoRupeeAmountAndSortsItLastByAmount() throws Exception {
        JsonNode rows = api.getJson("/api/v1/transactions?size=50&sort=amount,desc", 200).get("content");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("id").asText()).isEqualTo(rupee.toString());
        JsonNode fx = rows.get(1);
        assertThat(fx.get("id").asText()).isEqualTo(foreign.toString());
        assertThat(fx.get("amount").isNull()).isTrue();
        assertDecimal("-20", fx.get("originalAmount"));
        assertThat(fx.get("originalCurrency").asText()).isEqualTo("USD");

        JsonNode asc = api.getJson("/api/v1/transactions?size=50&sort=amount,asc", 200).get("content");
        assertThat(asc.get(1).get("id").asText()).isEqualTo(foreign.toString());

        JsonNode one = api.getJson("/api/v1/transactions/" + foreign, 200);
        assertThat(one.get("amount").isNull()).isTrue();
    }

    @Test
    void aLeadingForeignChargeShowsTheOpeningBalanceAndLaterRowsSkipIt() throws Exception {
        UUID leading = txn(today.withDayOfMonth(1).minusDays(3), null, new BigDecimal("5.00"), "USD", "OPENAI");
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode row : api.getJson("/api/v1/transactions?size=50", 200).get("content")) {
            byId.put(row.get("id").asText(), row);
        }
        assertDecimal("1000", byId.get(leading.toString()).get("balance"));
        assertDecimal("900", byId.get(rupee.toString()).get("balance"));
        assertDecimal("900", byId.get(foreign.toString()).get("balance"));
    }

    @Test
    void aBreakdownReportAndAKpiWithItsCsvExportCountOnlyRupeeRows() throws Exception {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", "bar");
        chart.put("dimension", Map.of("field", "category"));
        chart.put("measure", Map.of("field", "spend", "aggregation", "sum"));
        chart.put("filters", List.of());
        JsonNode byCategory = api.postJson("/api/v1/reports/data",
                Map.of("type", "CHART", "datasource", "transactions", "definition", chart), 200);
        assertThat(byCategory.get("categories")).hasSize(1);
        assertDecimal("100", byCategory.get("series").get(0).get("data").get(0));

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("measure", "spend");
        kpi.put("aggregation", "count");
        kpi.put("filters", List.of());
        Map<String, Object> request = Map.of("type", "KPI", "datasource", "transactions", "definition", kpi);
        assertDecimal("1", api.postJson("/api/v1/reports/data", request, 200).get("value"));

        MockHttpServletResponse csv = api.post("/api/v1/reports/underlying/csv", request);
        assertThat(csv.getStatus()).isEqualTo(200);
        String text = new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).contains("SWIGGY").doesNotContain("GITHUB");
    }

    @Test
    void anEditMayLeaveTheAmountUnknownOrSetItButARupeeRowStillNeedsOne() throws Exception {
        Map<String, Object> keep = edit(null, "GitHub");
        assertThat(api.putJson("/api/v1/transactions/" + foreign, keep, 200).get("amount").isNull()).isTrue();
        Transaction stillUnknown = transactionRepository.findById(foreign).orElseThrow();
        assertThat(stillUnknown.getAmount()).isNull();
        assertThat(stillUnknown.getType()).isEqualTo(TransactionType.DEBIT);

        JsonNode set = api.putJson("/api/v1/transactions/" + foreign, edit(new BigDecimal("-928.08"), "GitHub"), 200);
        assertDecimal("-928.08", set.get("amount"));
        assertDecimal("-20", set.get("originalAmount"));
        assertDecimal("-28.08", api.getJson("/api/v1/accounts/" + bank.getId(), 200).get("balance"));

        api.putJson("/api/v1/transactions/" + rupee, edit(null, "Swiggy"), 400);
        assertThat(transactionRepository.findById(rupee).orElseThrow().getAmount()).isEqualByComparingTo("100.00");
    }

    private Map<String, Object> edit(BigDecimal amount, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", bank.getId());
        body.put("date", today.withDayOfMonth(1).toString());
        body.put("amount", amount);
        body.put("description", description);
        return body;
    }

    private UUID txn(LocalDate date, BigDecimal amount, BigDecimal originalAmount, String currency, String text) {
        Transaction t = new Transaction();
        t.setUser(bank.getUser());
        t.setAccount(bank);
        t.setDate(date);
        t.setAmount(amount);
        t.setOriginalAmount(originalAmount);
        t.setOriginalCurrency(currency);
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.gmail_transaction_alert);
        t.setSourcedDescription(text);
        t.setCategoryKey("food");
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t).getId();
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertThat(actual).as("value").isNotNull();
        assertThat(actual.decimalValue()).isEqualByComparingTo(expected);
    }
}
