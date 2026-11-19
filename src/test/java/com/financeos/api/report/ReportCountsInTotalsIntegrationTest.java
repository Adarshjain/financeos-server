package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.report.engine.TransactionQueryBuilder;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Counts as spend or income" (countsInTotals) over real persistence: every report executor and the chat
 * view's counts_in_totals column apply one rule. The chat view is Oracle only, so its column expression is
 * read from the migration, proven identical to the report constant, and run here over the same rows.
 *
 * <p>Fixture: plain debit 10, plain credit 20, uncategorised 30, excluded 40, transfer-link leg 50,
 * card-payment-link leg 60, self_transfer not flagged 70, self_transfer un-excluded by the user 80,
 * card_bill flagged excluded 90, reversal-link leg 100, refund-link leg 110, top_up flagged and user-set 120,
 * plain debit the user excluded 130.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportCountsInTotalsIntegrationTest {

    private static final String MIGRATION = "src/main/resources/db/migration/V189__chat_transactions_counts_in_totals.sql";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionLinkRepository linkRepository;

    private ApiTestClient api;
    private UUID userId;
    private final Map<String, UUID> ids = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        String email = "counts-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main Bank", "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Account account = accountRepository.findById(bank).orElseThrow();

        add(account, "debit", "10", TransactionType.DEBIT, "food", false, false);
        add(account, "credit", "20", TransactionType.CREDIT, "salary", false, false);
        add(account, "uncategorised", "30", TransactionType.DEBIT, null, false, false);
        add(account, "excluded", "40", TransactionType.DEBIT, "food", true, true);
        add(account, "transferLeg", "50", TransactionType.DEBIT, "food", false, false);
        add(account, "cardPaymentLeg", "60", TransactionType.DEBIT, "food", false, false);
        add(account, "selfTransferDefault", "70", TransactionType.DEBIT, "self_transfer", false, false);
        add(account, "selfTransferUnexcluded", "80", TransactionType.DEBIT, "self_transfer", false, true);
        add(account, "cardBillExcluded", "90", TransactionType.DEBIT, "card_bill", true, false);
        add(account, "reversalLeg", "100", TransactionType.CREDIT, "food", false, false);
        add(account, "refundLeg", "110", TransactionType.CREDIT, "refund", false, false);
        add(account, "topUpUserExcluded", "120", TransactionType.DEBIT, "top_up", true, true);
        add(account, "plainUserExcluded", "130", TransactionType.DEBIT, "food", true, true);
        link(LinkType.TRANSFER, "transferLeg");
        link(LinkType.CC_PAYMENT, "cardPaymentLeg");
        link(LinkType.REVERSAL, "reversalLeg");
        link(LinkType.REFUND, "refundLeg");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    /** Counts: plain rows, uncategorised, a row the user un-excluded, and a refund-linked leg (only transfer, card payment and reversal links are legs). */
    private Set<UUID> expected() {
        Set<UUID> set = new HashSet<>();
        for (String name : List.of("debit", "credit", "uncategorised", "selfTransferUnexcluded", "refundLeg")) {
            set.add(ids.get(name));
        }
        return set;
    }

    @Test
    void theRuleCountsPlainRowsUncategorisedAndUserUnexcludedRowsAndNothingElse() throws Exception {
        JsonNode underlying = api.postJson("/api/v1/reports/underlying", kpi(true), 200);

        assertEquals(expected(), rowIds(underlying.get("table")));
        assertEquals(expected().size(), underlying.get("rowCount").asLong());
    }

    @Test
    void isFalseIsTheExactComplement() throws Exception {
        JsonNode underlying = api.postJson("/api/v1/reports/underlying", kpi(false), 200);

        Set<UUID> notCounted = new HashSet<>(ids.values());
        notCounted.removeAll(expected());
        assertEquals(notCounted, rowIds(underlying.get("table")));
    }

    @Test
    void kpiChartAndTableAgreeWithTheDrillDown() throws Exception {
        // Spend view: debits 10 + 30 + 80, credits (20, 110) negative.
        JsonNode kpi = api.postJson("/api/v1/reports/data", kpi(true), 200);
        assertEquals(0, new BigDecimal("10").add(new BigDecimal("30")).add(new BigDecimal("80"))
                .subtract(new BigDecimal("20")).subtract(new BigDecimal("110")).compareTo(kpi.get("value").decimalValue()));

        Map<String, Object> def = new LinkedHashMap<>();
        def.put("chartType", "bar");
        def.put("dimension", Map.of("field", "type"));
        def.put("measure", Map.of("field", "amount", "aggregation", "count"));
        def.put("filters", List.of(Map.of("field", "countsInTotals", "operator", "is", "value", true)));
        JsonNode chart = api.postJson("/api/v1/reports/data",
                Map.of("type", "CHART", "datasource", "transactions", "definition", def), 200);
        int total = 0;
        for (JsonNode v : chart.get("series").get(0).get("data")) {
            total += v.decimalValue().intValue();
        }
        assertEquals(expected().size(), total);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mode", "raw");
        raw.put("columns", List.of("spend"));
        raw.put("filters", List.of(Map.of("field", "countsInTotals", "operator", "is", "value", true)));
        raw.put("sort", List.of());
        JsonNode table = api.postJson("/api/v1/reports/data",
                Map.of("type", "TABLE", "datasource", "transactions", "definition", raw), 200);
        assertEquals(expected(), rowIds(table));
    }

    @Test
    void theChatViewColumnReturnsTheSameRowsAsTheReportFilter() throws Exception {
        String viewExpression = viewExpression();
        assertEquals(TransactionQueryBuilder.COUNTS_IN_TOTALS, viewExpression, "the view and the report filter use one rule");

        List<String> viewIds = jdbc.queryForList("SELECT t.id FROM transactions t WHERE t.user_id = ?"
                + " AND t.amount IS NOT NULL AND " + viewExpression + " = 1", String.class, userId.toString());
        Set<UUID> fromView = new HashSet<>();
        viewIds.forEach(i -> fromView.add(UUID.fromString(i)));

        JsonNode underlying = api.postJson("/api/v1/reports/underlying", kpi(true), 200);
        assertEquals(rowIds(underlying.get("table")), fromView);
        assertEquals(expected(), fromView);
    }

    @Test
    void theChatViewMigrationAddsTheColumnAtTheEndAndKeepsTheRest() throws Exception {
        String sql = Files.readString(Path.of(MIGRATION), StandardCharsets.UTF_8);
        String previous = Files.readString(Path.of("src/main/resources/db/migration/V165__chat_transactions_view_foreign.sql"),
                StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE OR REPLACE VIEW v_chat_transactions"));
        assertTrue(sql.contains("ch.relationship AS card_relationship,\n"));
        assertEquals(previous.substring(previous.indexOf("FROM transactions t")), sql.substring(sql.indexOf("FROM transactions t")),
                "same FROM and WHERE as before");
    }

    // ------------------------------------------------------------------ helpers

    private static String viewExpression() throws Exception {
        String sql = Files.readString(Path.of(MIGRATION), StandardCharsets.UTF_8);
        int end = sql.indexOf(" AS counts_in_totals");
        int start = sql.lastIndexOf("\n    ", end) + "\n    ".length();
        return sql.substring(start, end);
    }

    private void add(Account account, String name, String amount, TransactionType type, String category,
                     boolean excluded, boolean setByUser) {
        Transaction t = new Transaction(account, AppTime.today(), new BigDecimal(amount), name,
                TransactionSource.manual, type, false, false);
        t.setUser(account.getUser());
        t.setCategoryKey(category);
        UUID id = transactionRepository.save(t).getId();
        jdbc.update("UPDATE transactions SET is_excluded = ?, exclude_set_by_user = ? WHERE id = ?",
                excluded ? 1 : 0, setByUser ? 1 : 0, id.toString());
        ids.put(name, id);
    }

    private void link(LinkType type, String member) {
        TransactionLink link = new TransactionLink();
        link.setUser(userRepository.findById(userId).orElseThrow());
        link.setType(type);
        link.setCreatedBy(LinkOrigin.USER);
        UUID linkId = linkRepository.save(link).getId();
        jdbc.update("INSERT INTO transaction_link_members (link_id, transaction_id, is_anchor) VALUES (?, ?, 1)",
                linkId.toString(), ids.get(member).toString());
    }

    private static Map<String, Object> kpi(boolean value) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "spend");
        def.put("aggregation", "sum");
        def.put("filters", List.of(Map.of("field", "countsInTotals", "operator", "is", "value", value)));
        return Map.of("type", "KPI", "datasource", "transactions", "definition", def);
    }

    private static Set<UUID> rowIds(JsonNode table) {
        Set<UUID> out = new HashSet<>();
        table.get("rows").forEach(r -> out.add(UUID.fromString(r.get("id").asText())));
        return out;
    }
}
