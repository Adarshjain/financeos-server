package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
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

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: with a single-connection pool, running a report, dashboard widget or KPI underlying
 * over a section-running datasource (net worth, obligations) must not hold a connection in an outer
 * transaction while its sections take theirs, or every section times out and the rows silently go
 * missing.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=1000"})
@AutoConfigureMockMvc
class ReportSectionPoolIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "report-pool-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Pool Bank", "last4", "4321",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        LocalDate today = AppTime.today();
        Map<String, Object> loan = new HashMap<>();
        loan.put("name", "Pool loan");
        loan.put("loanType", "home");
        loan.put("lender", "HDFC");
        loan.put("principal", "120000");
        loan.put("annualRatePct", "9.5");
        loan.put("rateType", "fixed");
        loan.put("tenureMonths", 24);
        loan.put("startDate", today.minusDays(20).toString());
        loan.put("firstEmiDate", today.plusDays(10).toString());
        api.create("/api/v1/loans", loan);
        Map<String, Object> lending = new HashMap<>();
        lending.put("newCounterpartyName", "Pool Rahul");
        lending.put("direction", "lent");
        lending.put("amount", "5000");
        lending.put("entryDate", today.minusDays(5).toString());
        lending.put("expectedReturnDate", today.plusDays(20).toString());
        api.create("/api/v1/lendings", lending);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void savedNetWorthReport_listsAccountLoanAndLendingRows() throws Exception {
        UUID id = api.create("/api/v1/reports", Map.of("name", "NW", "type", "TABLE", "datasource", "net_worth",
                "definition", rawTable("name", "kind")));

        JsonNode table = within(() -> api.postJson("/api/v1/reports/" + id + "/data", null, 200));

        assertEquals(List.of("Pool Bank", "Pool Rahul", "Pool loan"), sorted(texts(table.get("rows"), "name")));
    }

    @Test
    void adHocNetWorthReport_listsAccountLoanAndLendingRows() throws Exception {
        JsonNode table = within(() -> api.postJson("/api/v1/reports/data", Map.of("type", "TABLE",
                "datasource", "net_worth", "definition", rawTable("name", "kind")), 200));

        assertEquals(List.of("Pool Bank", "Pool Rahul", "Pool loan"), sorted(texts(table.get("rows"), "name")));
    }

    @Test
    void savedObligationsReport_listsTheEmiRow() throws Exception {
        UUID id = api.create("/api/v1/reports", Map.of("name", "Obl", "type", "TABLE", "datasource", "obligations",
                "definition", rawTable("title", "kind")));

        JsonNode table = within(() -> api.postJson("/api/v1/reports/" + id + "/data", null, 200));

        assertTrue(texts(table.get("rows"), "kind").contains("emi"), table.toString());
    }

    @Test
    void adHocObligationsReport_listsTheEmiRow() throws Exception {
        JsonNode table = within(() -> api.postJson("/api/v1/reports/data", Map.of("type", "TABLE",
                "datasource", "obligations", "definition", rawTable("title", "kind")), 200));

        assertTrue(texts(table.get("rows"), "kind").contains("emi"), table.toString());
    }

    @Test
    void dashboardBuiltinWidgets_runOverSectionDatasources() throws Exception {
        JsonNode netWorth = within(() -> api.postJson("/api/v1/dashboards/builtins/net_worth/data", null, 200));
        JsonNode upcoming = within(() -> api.postJson("/api/v1/dashboards/builtins/upcoming/data", null, 200));

        // 1000 bank + 5000 lent - the loan's outstanding principal: more than the bank alone.
        assertTrue(netWorth.get("value").decimalValue().compareTo(java.math.BigDecimal.valueOf(1000)) != 0,
                netWorth.toString());
        assertTrue(texts(upcoming.get("rows"), "title").stream().anyMatch(t -> t.contains("Pool loan")), upcoming.toString());
    }

    @Test
    void dashboardContainingSectionReports_loadsAndItsWidgetsRun() throws Exception {
        UUID reportId = api.create("/api/v1/reports", Map.of("name", "NW", "type", "TABLE", "datasource", "net_worth",
                "definition", rawTable("name", "kind")));
        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("id", UUID.randomUUID().toString());
        widget.put("reportId", reportId.toString());
        widget.put("layout", Map.of("x", 0, "y", 0, "w", 6, "h", 4));
        UUID dashboardId = api.create("/api/v1/dashboards", Map.of("name", "Pool dash", "widgets", List.of(widget)));

        JsonNode dashboard = within(() -> api.getJson("/api/v1/dashboards/" + dashboardId, 200));
        JsonNode table = within(() -> api.postJson("/api/v1/reports/" + reportId + "/data", null, 200));

        assertEquals(1, dashboard.get("widgets").size());
        assertEquals(3, table.get("rows").size());
    }

    @Test
    void kpiUnderlyingOverNetWorth_listsLoanAndLendingRows() throws Exception {
        JsonNode body = within(() -> api.postJson("/api/v1/dashboards/builtins/net_worth/underlying", null, 200));

        assertEquals(3, body.get("rowCount").asLong(), body.toString());
    }

    @Test
    void kpiUnderlyingOverNetWorth_savedAndAdHoc() throws Exception {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("measure", "signedValue");
        kpi.put("aggregation", "sum");
        kpi.put("filters", List.of());
        UUID id = api.create("/api/v1/reports", Map.of("name", "NW KPI", "type", "KPI", "datasource", "net_worth",
                "definition", kpi));

        JsonNode saved = within(() -> api.postJson("/api/v1/reports/" + id + "/underlying", null, 200));
        JsonNode adHoc = within(() -> api.postJson("/api/v1/reports/underlying",
                Map.of("type", "KPI", "datasource", "net_worth", "definition", kpi), 200));

        assertEquals(3, saved.get("rowCount").asLong());
        assertEquals(3, adHoc.get("rowCount").asLong());
    }

    // ------------------------------------------------------------------ helpers

    private static <T> T within(Callable<T> call) {
        return assertTimeoutPreemptively(Duration.ofSeconds(90), call::call);
    }

    private static Map<String, Object> rawTable(String... columns) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("mode", "raw");
        def.put("columns", List.of(columns));
        def.put("filters", List.of());
        def.put("sort", List.of());
        return def;
    }

    private static List<String> texts(JsonNode rows, String key) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get(key).asText()));
        return out;
    }

    private static List<String> sorted(List<String> values) {
        assertFalse(values.isEmpty());
        return values.stream().sorted().toList();
    }
}
