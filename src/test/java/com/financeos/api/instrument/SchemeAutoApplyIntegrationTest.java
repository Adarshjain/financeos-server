package com.financeos.api.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.investment.dto.ReconcileHoldingsSnapshot;
import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.corporateaction.detect.OversoldSuggestionService;
import com.financeos.domain.instrument.corporateaction.detect.SchemeAutoApply;
import com.financeos.domain.instrument.corporateaction.detect.SchemeEvidence;
import com.financeos.domain.instrument.corporateaction.detect.SchemeEvidenceChild;
import com.financeos.domain.instrument.corporateaction.detect.SchemeEvidenceChildRepository;
import com.financeos.domain.instrument.corporateaction.detect.SchemeEvidenceRepository;
import com.financeos.domain.investment.reconcile.BrokerHoldingSnapshotRepository;
import com.financeos.domain.investment.reconcile.HoldingsSnapshotStore;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A demerger the company's notices describe is applied on its own only when the user's holdings agree in every broker account
 * that held the parent; undo takes the action and the child holding it created away again. Runs the V186 evidence entities
 * and repositories and the V187 snapshot, check and applied-because columns against the integration database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SchemeAutoApplyIntegrationTest {

    private static final String PATH = "/api/v1/corporate-action-suggestions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private SchemeAutoApply autoApply;
    @Autowired private SchemeEvidenceRepository evidence;
    @Autowired private SchemeEvidenceChildRepository evidenceChildren;
    @Autowired private HoldingsSnapshotStore snapshotStore;
    @Autowired private BrokerHoldingSnapshotRepository snapshotRepo;
    @Autowired private OversoldSuggestionService oversold;
    @Autowired private com.financeos.domain.holding.HoldingRepository holdingRepo;
    @Autowired private com.financeos.domain.investment.InvestmentTransactionRepository txRepo;
    @Autowired private com.financeos.domain.account.AccountRepository accountRepo;
    @Autowired private com.financeos.domain.instrument.InstrumentRepository instrumentRepo;
    @Autowired private com.financeos.domain.investment.reconcile.BrokerReconciliationService reconciliation;
    @Autowired private com.financeos.domain.instrument.corporateaction.CorporateActionRepository actionRepo;
    @SpyBean private com.financeos.domain.instrument.corporateaction.detect.StockNotTradingRule notTradingRule;

    private ApiTestClient alice;
    private UUID aliceId;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private final List<UUID> evidenceIds = new ArrayList<>();
    private LocalDate today;
    private LocalDate ex;
    private LocalDate listed;
    private String tag;
    private UUID parent;
    private UUID child;
    private String parentSymbol;
    private String childIsin;
    private UUID broker;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        ex = today.minusDays(60);
        listed = ex.plusDays(20);
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String a = "saa-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        parent = stock("Parent Co", "PAR", "INE" + tag + "01");
        childIsin = "INE" + tag + "02";
        child = stock("Child Co", "CHD", childIsin);
        parentSymbol = ("PAR" + tag.substring(0, 4)).toUpperCase(Locale.ROOT);
        broker = alice.create("/api/v1/accounts", Map.of("type", "broker", "name", "Zerodha main", "provider", "Zerodha",
                "clientId", "SAA1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        trade(parent, "buy", "100", "100", today.minusDays(200));
    }

    @AfterEach
    void tearDown() {
        for (UUID id : evidenceIds) {
            evidenceChildren.findForEvidence(List.of(id)).forEach(evidenceChildren::delete);
            evidence.deleteById(id);
        }
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    private UUID stock(String name, String symbol, String isin) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "stock");
        body.put("name", name + " " + tag);
        body.put("symbol", symbol + tag.substring(0, 4));
        body.put("exchange", "NSE");
        body.put("isin", isin);
        UUID id = alice.create("/api/v1/instruments", body);
        instrumentIds.add(id);
        return id;
    }

    private void trade(UUID instrument, String type, String qty, String price, LocalDate date) throws Exception {
        alice.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(), "instrumentId", instrument.toString(),
                "type", type, "quantity", qty, "price", price, "tradeDate", date.toString()));
    }

    /** The pending demerger the detector would have held, and the company's notice reading for it. */
    private UUID pendingDemerger(boolean noticeCost) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO corporate_action_suggestions (id, user_id, instrument_id, kind, ex_date, subject, status, reason, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", id.toString(), aliceId.toString(), parent.toString(), "demerger", java.sql.Date.valueOf(ex),
                "Demerger", "pending", "NEEDS_DETAILS", Timestamp.from(Instant.now()));
        SchemeEvidence e = new SchemeEvidence();
        e.setParentSymbol(parentSymbol);
        e.setKind("demerger");
        e.setExDate(ex);
        e.setParentCostPct(new BigDecimal("80"));
        e.setCostKind(noticeCost ? "NOTICE" : "ESTIMATE");
        e.setComplete(noticeCost);
        e.setCheckedAt(Instant.now());
        e = evidence.save(e);
        evidenceIds.add(e.getId());
        SchemeEvidenceChild c = new SchemeEvidenceChild();
        c.setEvidenceId(e.getId());
        c.setChildOrder(0);
        c.setChildSymbol("CHD" + tag.substring(0, 4));
        c.setChildIsin(childIsin);
        c.setChildName("Child Co " + tag);
        c.setListedOn(listed);
        c.setRatioFrom(2);
        c.setRatioTo(1);
        c.setRatioUrl("https://nsearchives.example/corporate/rd.pdf");
        c.setRatioNoticeDate(ex.minusDays(10));
        c.setChildCostPct(new BigDecimal("20"));
        evidenceChildren.save(c);
        return id;
    }

    private void snapshot(String childQty) {
        snapshotStore.replace(aliceId, broker, new ReconcileHoldingsSnapshot(today.minusDays(1), List.of(
                new ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow(childIsin, "CHD", new BigDecimal(childQty)),
                new ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow("INE0UNKNOWN01", "NOTINAPP", new BigDecimal("7")))));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private JsonNode appliedItem(UUID suggestionId) throws Exception {
        for (JsonNode n : alice.getJson(PATH, 200).get("appliedAutomatically")) {
            if (n.get("id").asText().equals(suggestionId.toString())) {
                return n;
            }
        }
        return null;
    }

    @Test
    void aSnapshotThatAgreesAppliesTheDemergerAndUndoTakesItAndTheChildHoldingBack() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50"); // floor(100 x 1 / 2) = 50 child shares

        SchemeAutoApply.Summary out = autoApply.runForUser(aliceId);

        assertEquals(1, out.applied());
        assertEquals("auto_applied", jdbc.queryForObject("SELECT status FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString()));
        String because = jdbc.queryForObject("SELECT applied_because FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString());
        assertTrue(because.contains("rd.pdf") && because.contains("SNAPSHOT") && because.contains("Zerodha main") && because.contains("qty 50"), because);
        assertEquals(1, count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ? AND instrument_id = ? AND type = 'demerger' AND source = 'auto' "
                + "AND target_instrument_id = ? AND ratio_from = 2 AND ratio_to = 1 AND cost_allocation_pct = 20", aliceId.toString(), parent.toString(), child.toString()));
        assertEquals(1, count("SELECT COUNT(*) FROM holdings WHERE user_id = ? AND instrument_id = ?", aliceId.toString(), child.toString()));

        JsonNode item = appliedItem(suggestion);
        assertTrue(item != null, "the Applied list shows it");
        assertTrue(item.get("appliedBecause").asText().contains("SNAPSHOT"));
        JsonNode check = item.get("details").get("holdingsCheck").get(0);
        assertEquals("SNAPSHOT", check.get("source").asText());
        assertTrue(check.get("agrees").asBoolean());
        assertEquals(0, new BigDecimal("50").compareTo(check.get("foundQty").decimalValue()));
        assertEquals("Zerodha main", check.get("accountName").asText());

        alice.postJson(PATH + "/" + suggestion + "/undo", null, 200);

        assertEquals(0, count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ? AND instrument_id = ?", aliceId.toString(), parent.toString()));
        assertEquals(0, count("SELECT COUNT(*) FROM holdings WHERE user_id = ? AND instrument_id = ?", aliceId.toString(), child.toString()), "the child holding it made is gone");
        assertEquals("undone", jdbc.queryForObject("SELECT status FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString()));
        assertEquals(0, autoApply.runForUser(aliceId).applied(), "an undone one is not applied again");
    }

    @Test
    void undoKeepsAChildHoldingThatHasTrades() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        autoApply.runForUser(aliceId);
        trade(child, "buy", "3", "10", today.minusDays(5));

        alice.postJson(PATH + "/" + suggestion + "/undo", null, 200);

        assertEquals(1, count("SELECT COUNT(*) FROM holdings WHERE user_id = ? AND instrument_id = ?", aliceId.toString(), child.toString()));
    }

    @Test
    void theTradebookAloneAgreesAndTheOversoldItemOfTheChildClearsOnItsOwn() throws Exception {
        UUID suggestion = pendingDemerger(true);
        // sold the 50 that arrived; the app does not know where they came from (the API refuses such a sell, so it is stored directly)
        var holding = new com.financeos.domain.holding.Holding(accountRepo.findById(broker).orElseThrow(), instrumentRepo.findById(child).orElseThrow(), null);
        holding.setUser(userRepository.getReferenceById(aliceId));
        holding = holdingRepo.save(holding);
        var sell = new com.financeos.domain.investment.InvestmentTransaction();
        sell.setUser(userRepository.getReferenceById(aliceId));
        sell.setHolding(holding);
        sell.setType(com.financeos.domain.investment.InvestmentTransactionType.sell);
        sell.setQuantity(new BigDecimal("50"));
        sell.setPrice(new BigDecimal("12"));
        sell.setTradeDate(listed.plusDays(2));
        txRepo.save(sell);
        oversold.sync(aliceId);
        assertEquals(1, count("SELECT COUNT(*) FROM corporate_action_suggestions WHERE user_id = ? AND instrument_id = ? AND kind = 'oversold' AND status = 'pending'",
                aliceId.toString(), child.toString()));

        SchemeAutoApply.Summary out = autoApply.runForUser(aliceId);

        assertEquals(1, out.applied());
        assertTrue(jdbc.queryForObject("SELECT applied_because FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString()).contains("TRADEBOOK"));
        oversold.sync(aliceId);
        assertEquals(0, count("SELECT COUNT(*) FROM corporate_action_suggestions WHERE user_id = ? AND instrument_id = ? AND kind = 'oversold' AND status = 'pending'",
                aliceId.toString(), child.toString()), "recomputed: no longer oversold");
    }

    @Test
    void aDisagreeingSnapshotLeavesItPendingAndShowsTheCheck() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("49");

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals("pending", jdbc.queryForObject("SELECT status FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString()));
        assertEquals(0, count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ?", aliceId.toString()));
        JsonNode item = null;
        for (JsonNode n : alice.getJson(PATH, 200).get("needsReview")) {
            if (n.get("id").asText().equals(suggestion.toString())) {
                item = n;
            }
        }
        assertTrue(item != null);
        JsonNode check = item.get("details").get("holdingsCheck").get(0);
        assertFalse(check.get("agrees").asBoolean());
        assertEquals(0, new BigDecimal("50").compareTo(check.get("expectedQty").decimalValue()));
        assertEquals(0, new BigDecimal("49").compareTo(check.get("foundQty").decimalValue()));
    }

    @Test
    void aPriceEstimateNeverAppliesEvenWhenEverythingAgrees() throws Exception {
        UUID suggestion = pendingDemerger(false);
        snapshot("50");

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals("pending", jdbc.queryForObject("SELECT status FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString()));
    }

    @Test
    void aSecondAccountWithoutEvidenceBlocksIt() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        UUID second = alice.create("/api/v1/accounts", Map.of("type", "broker", "name", "Groww", "provider", "Groww",
                "clientId", "SAA2", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        alice.create("/api/v1/investments/transactions", Map.of("brokerAccountId", second.toString(), "instrumentId", parent.toString(),
                "type", "buy", "quantity", "10", "price", "100", "tradeDate", today.minusDays(150).toString()));

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals("pending", jdbc.queryForObject("SELECT status FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString()));
    }

    @Test
    void aDemergerTheUserAlreadyEnteredByHandIsNotTouched() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        alice.create("/api/v1/instruments/" + parent + "/corporate-actions", Map.of("type", "demerger", "ratioFrom", 2, "ratioTo", 1,
                "exDate", ex.plusDays(1).toString(), "targetInstrumentId", child.toString(), "costAllocationPct", 15));

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals(1, count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ? AND type = 'demerger'", aliceId.toString()));
        assertEquals("manual", jdbc.queryForObject("SELECT source FROM corporate_actions WHERE user_id = ?", String.class, aliceId.toString()));
        assertTrue(suggestion != null);
    }

    @Test
    void aDismissedSuggestionIsNeverApplied() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        jdbc.update("UPDATE corporate_action_suggestions SET status = 'dismissed' WHERE id = ?", suggestion.toString());

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals(0, count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ?", aliceId.toString()));
    }

    @Test
    void severalChildrenAreNeverAppliedOnTheirOwn() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        SchemeEvidence e = evidence.findScheme(parentSymbol, "demerger", ex).orElseThrow();
        SchemeEvidenceChild second = new SchemeEvidenceChild();
        second.setEvidenceId(e.getId());
        second.setChildOrder(1);
        second.setChildSymbol("OTH" + tag.substring(0, 4));
        second.setChildIsin("INE" + tag + "09");
        second.setChildName("Other Co " + tag);
        second.setRatioFrom(1);
        second.setRatioTo(1);
        evidenceChildren.save(second);

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals("pending", jdbc.queryForObject("SELECT status FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString()));
    }

    @Test
    void aSnapshotIsReplacedNotAppendedAndKeepsEveryRow() {
        snapshot("50");
        snapshot("40");

        var rows = snapshotRepo.findOwned(aliceId);

        assertEquals(2, rows.size(), "the latest snapshot only, including the stock the app does not have");
        assertTrue(rows.stream().anyMatch(r -> "INE0UNKNOWN01".equals(r.getIsin())));
        assertTrue(rows.stream().anyMatch(r -> childIsin.equals(r.getIsin()) && r.getQuantity().compareTo(new BigDecimal("40")) == 0));
        assertEquals(today.minusDays(1), rows.get(0).getAsOfDate());
    }

    @Test
    void aMergerOfAStoppedStockAppliesWhenTheSnapshotShowsTheAcquirerShares() throws Exception {
        // the parent stopped trading; the notices name the acquirer (the "child" instrument), ratio 5 for 2 and a record date
        UUID acquirer = child;
        jdbc.update("UPDATE instruments SET price_status = 'not_trading', price_status_at = ? WHERE id = ?", Timestamp.from(Instant.now()), parent.toString());
        SchemeEvidence e = new SchemeEvidence();
        e.setParentSymbol(parentSymbol);
        e.setKind("merger");
        e.setRecordDate(ex);
        e.setComplete(true);
        e.setCheckedAt(Instant.now());
        e = evidence.save(e);
        evidenceIds.add(e.getId());
        SchemeEvidenceChild c = new SchemeEvidenceChild();
        c.setEvidenceId(e.getId());
        c.setChildOrder(0);
        c.setChildSymbol("CHD" + tag.substring(0, 4));
        c.setChildIsin(childIsin);
        c.setChildName("Child Co " + tag);
        c.setRatioFrom(2);
        c.setRatioTo(5);
        c.setRatioUrl("https://nsearchives.example/corporate/merger.pdf");
        c.setRatioNoticeDate(ex);
        evidenceChildren.save(c);
        snapshot("250"); // 100 x 5 / 2
        feedPrice(parent, ex.minusDays(3)); // the record date must lie close to the stopped stock's last price

        SchemeAutoApply.Summary out = autoApply.runForUser(aliceId);

        assertEquals(1, out.applied());
        assertEquals(1, count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ? AND instrument_id = ? AND type = 'merger' AND source = 'auto' AND target_instrument_id = ?",
                aliceId.toString(), parent.toString(), acquirer.toString()));
        String suggestionId = jdbc.queryForObject("SELECT id FROM corporate_action_suggestions WHERE user_id = ? AND kind = 'merger' AND status = 'auto_applied'",
                String.class, aliceId.toString());
        assertTrue(appliedItem(UUID.fromString(suggestionId)) != null, "the Applied list shows the merger");

        alice.postJson(PATH + "/" + suggestionId + "/undo", null, 200);

        assertEquals(0, count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ? AND type = 'merger'", aliceId.toString()));
        assertEquals(0, autoApply.runForUser(aliceId).applied());
    }

    @Test
    void aReconcileCommitKeepsTheHoldingsFileOfThatAccountAndReplacesThePreviousOne() throws Exception {
        com.financeos.core.security.UserContext.setCurrentUserId(aliceId);
        try {
            var first = new ReconcileHoldingsSnapshot(today.minusDays(3), List.of(
                    new ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow(childIsin, "CHD", new BigDecimal("50")),
                    new ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow(null, "NOISIN", new BigDecimal("2"))));
            reconciliation.commit(new com.financeos.api.investment.dto.ReconcileCommitRequest(com.financeos.domain.investment.reconcile.Broker.zerodha,
                    broker, List.of(), List.of(), List.of(), first));
            assertEquals(2, snapshotRepo.findOwned(aliceId).size());

            // a commit without a holdings file leaves it as it was
            reconciliation.commit(new com.financeos.api.investment.dto.ReconcileCommitRequest(com.financeos.domain.investment.reconcile.Broker.zerodha,
                    broker, List.of(), List.of(), List.of()));
            assertEquals(2, snapshotRepo.findOwned(aliceId).size());

            var second = new ReconcileHoldingsSnapshot(today, List.of(new ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow(childIsin, "CHD", new BigDecimal("45"))));
            reconciliation.commit(new com.financeos.api.investment.dto.ReconcileCommitRequest(com.financeos.domain.investment.reconcile.Broker.zerodha,
                    broker, List.of(), List.of(), List.of(), second));

            var rows = snapshotRepo.findOwned(aliceId);
            assertEquals(1, rows.size());
            assertEquals(today, rows.get(0).getAsOfDate());
            assertEquals(0, new BigDecimal("45").compareTo(rows.get(0).getQuantity()));
        } finally {
            com.financeos.core.security.UserContext.clear();
        }
    }

    @Test
    void deletingTheAccountRemovesTheSnapshotAndCheckRows() throws Exception {
        String b = "saa-b-" + UUID.randomUUID() + "@example.test";
        ApiTestClient bob = ApiTestClient.signUp(mockMvc, mapper, b);
        UUID bobId = userRepository.findByEmail(b).orElseThrow().getId();
        snapshotStore.replace(bobId, UUID.randomUUID(), new ReconcileHoldingsSnapshot(today, List.of(new ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow("INE0X", "X", BigDecimal.ONE))));
        jdbc.update("INSERT INTO corporate_scheme_checks (id, user_id, instrument_id, kind, ex_date, agrees, checks_json, checked_at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), bobId.toString(), parent.toString(), "demerger", java.sql.Date.valueOf(ex), false, "[]", Timestamp.from(Instant.now()));
        assertEquals(1, count("SELECT COUNT(*) FROM broker_holding_snapshots WHERE user_id = ?", bobId.toString()));
        assertEquals(1, count("SELECT COUNT(*) FROM corporate_scheme_checks WHERE user_id = ?", bobId.toString()));

        UserDataCleanup.deleteUsers(jdbc, List.of(bobId));

        assertEquals(0, count("SELECT COUNT(*) FROM broker_holding_snapshots WHERE user_id = ?", bobId.toString()));
        assertEquals(0, count("SELECT COUNT(*) FROM corporate_scheme_checks WHERE user_id = ?", bobId.toString()));
        assertTrue(bob != null);
    }

    // ---- review fixes

    private void feedPrice(UUID instrument, LocalDate asOf) {
        jdbc.update("INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) VALUES (?, ?, ?, ?, 'YAHOO', CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), instrument.toString(), java.sql.Date.valueOf(asOf), new BigDecimal("100"));
    }

    private String status(UUID suggestion) {
        return jdbc.queryForObject("SELECT status FROM corporate_action_suggestions WHERE id = ?", String.class, suggestion.toString());
    }

    private int demergerActions() {
        return count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ? AND type = 'demerger'", aliceId.toString());
    }

    @Test
    void aSecondRunAfterAnApplyIsANoOp() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");

        assertEquals(1, autoApply.runForUser(aliceId).applied());
        SchemeAutoApply.Summary again = autoApply.runForUser(aliceId);

        assertEquals(0, again.applied());
        assertEquals(1, demergerActions());
        assertEquals("auto_applied", status(suggestion));
    }

    @Test
    void twoRunsAtTheSameTimeApplyItOnce() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return autoApply.runForUser(aliceId).applied();
                }));
            }
            go.countDown();
            int applied = 0;
            for (java.util.concurrent.Future<Integer> f : runs) {
                applied += f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }

            assertEquals(1, applied);
            assertEquals(1, demergerActions());
            assertEquals("auto_applied", status(suggestion));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSuggestionDismissedDuringTheRunIsNotOverwritten() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        // the owner dismisses it after the run has read its pending list and before it applies
        org.mockito.Mockito.doAnswer(inv -> {
            jdbc.update("UPDATE corporate_action_suggestions SET status = 'dismissed' WHERE id = ?", suggestion.toString());
            return inv.callRealMethod();
        }).when(notTradingRule).unanswered(org.mockito.ArgumentMatchers.any());

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals("dismissed", status(suggestion));
        assertEquals(0, demergerActions());
    }

    @Test
    void aCostSplitSummingTo99Point6IsHeldForReviewWithItsValues() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        SchemeEvidence e = evidence.findScheme(parentSymbol, "demerger", ex).orElseThrow();
        for (SchemeEvidenceChild c : evidenceChildren.findForEvidence(List.of(e.getId()))) {
            c.setChildCostPct(new BigDecimal("19.6")); // 80 + 19.6
            evidenceChildren.save(c);
        }

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals("pending", status(suggestion));
        assertEquals(0, demergerActions());
        assertEquals(0, new BigDecimal("80").compareTo(evidence.findScheme(parentSymbol, "demerger", ex).orElseThrow().getParentCostPct()), "the values stay for the user");
    }

    @Test
    void aUserWithNothingPendingLoadsNothing() {
        // no pending demerger and no stopped stock: the run is empty
        SchemeAutoApply.Summary out = autoApply.runForUser(aliceId);

        assertEquals(0, out.considered());
        assertEquals(0, out.applied());
    }

    private void stoppedMerger(LocalDate recordDate) {
        jdbc.update("UPDATE instruments SET price_status = 'not_trading', price_status_at = ? WHERE id = ?", Timestamp.from(Instant.now()), parent.toString());
        SchemeEvidence e = new SchemeEvidence();
        e.setParentSymbol(parentSymbol);
        e.setKind("merger");
        e.setRecordDate(recordDate);
        e.setComplete(true);
        e.setCheckedAt(Instant.now());
        e = evidence.save(e);
        evidenceIds.add(e.getId());
        SchemeEvidenceChild c = new SchemeEvidenceChild();
        c.setEvidenceId(e.getId());
        c.setChildOrder(0);
        c.setChildSymbol("CHD" + tag.substring(0, 4));
        c.setChildIsin(childIsin);
        c.setChildName("Child Co " + tag);
        c.setRatioFrom(2);
        c.setRatioTo(5);
        c.setRatioUrl("https://nsearchives.example/corporate/merger.pdf");
        evidenceChildren.save(c);
        snapshot("250");
    }

    private int mergerActions() {
        return count("SELECT COUNT(*) FROM corporate_actions WHERE user_id = ? AND type = 'merger'", aliceId.toString());
    }

    @Test
    void aMergerRecordDateInTheFutureIsNotAppliedAndIsFlagged() throws Exception {
        LocalDate record = today.plusDays(3);
        stoppedMerger(record);
        feedPrice(parent, today.minusDays(2));

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals(0, mergerActions());
        assertTrue(evidence.findScheme(parentSymbol, "merger", null).orElseThrow().getMissing().contains("RECORD_DATE_OUT_OF_RANGE"));
    }

    @Test
    void aMergerRecordDateFarFromTheLastPriceIsNotApplied() throws Exception {
        stoppedMerger(ex);
        feedPrice(parent, ex.minusDays(46));

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals(0, mergerActions());
        assertTrue(evidence.findScheme(parentSymbol, "merger", null).orElseThrow().getMissing().contains("RECORD_DATE_OUT_OF_RANGE"));
    }

    @Test
    void aMergerWithNoPriceHistoryIsNotApplied() throws Exception {
        stoppedMerger(ex);

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals(0, mergerActions());
    }

    @Test
    void aMergerRecordDateWithinFortyFiveDaysOfTheLastPriceApplies() throws Exception {
        stoppedMerger(ex);
        feedPrice(parent, ex.minusDays(45));

        assertEquals(1, autoApply.runForUser(aliceId).applied());

        assertEquals(1, mergerActions());
    }

    @Test
    void aMergerDecidedBeforeTheApplyIsNotApplied() throws Exception {
        stoppedMerger(ex);
        feedPrice(parent, ex.minusDays(2));
        // the user marks it delisted after the run read the unanswered list
        org.mockito.Mockito.doAnswer(inv -> {
            Object result = inv.callRealMethod();
            alice.create("/api/v1/instruments/" + parent + "/corporate-actions", Map.of("type", "delisted", "ratioFrom", 1, "ratioTo", 1, "exDate", ex.toString()));
            return result;
        }).when(notTradingRule).unanswered(org.mockito.ArgumentMatchers.any());

        assertEquals(0, autoApply.runForUser(aliceId).applied());

        assertEquals(0, mergerActions());
    }

    @Test
    void undoKeepsAChildHoldingThatHasDividends() throws Exception {
        UUID suggestion = pendingDemerger(true);
        snapshot("50");
        autoApply.runForUser(aliceId);
        alice.create("/api/v1/investments/dividends", Map.of("brokerAccountId", broker.toString(), "instrumentId", child.toString(),
                "type", "dividend", "amount", 100, "payDate", today.minusDays(5).toString()));

        alice.postJson(PATH + "/" + suggestion + "/undo", null, 200);

        assertEquals(1, count("SELECT COUNT(*) FROM holdings WHERE user_id = ? AND instrument_id = ?", aliceId.toString(), child.toString()));
    }

    @Test
    void sameDayActionsComeBackInCreationOrder() throws Exception {
        alice.create("/api/v1/instruments/" + parent + "/corporate-actions", Map.of("type", "split", "ratioFrom", 1, "ratioTo", 2, "exDate", ex.toString()));
        alice.create("/api/v1/instruments/" + parent + "/corporate-actions", Map.of("type", "bonus", "ratioFrom", 1, "ratioTo", 2, "exDate", ex.toString()));
        // created in the opposite order of their ids
        jdbc.update("UPDATE corporate_actions SET created_at = ? WHERE user_id = ? AND type = 'bonus'", Timestamp.from(Instant.parse("2025-01-01T10:00:00Z")), aliceId.toString());
        jdbc.update("UPDATE corporate_actions SET created_at = ? WHERE user_id = ? AND type = 'split'", Timestamp.from(Instant.parse("2025-01-01T10:00:05Z")), aliceId.toString());

        var ordered = actionRepo.findByUser_IdAndInstrument_IdOrderByExDateAsc(aliceId, parent);
        var byInstruments = actionRepo.findOwnedByInstrumentIds(aliceId, List.of(parent));

        assertEquals(List.of("bonus", "split"), ordered.stream().map(a -> a.getType().name()).toList());
        assertEquals(List.of("bonus", "split"), byInstruments.stream().map(a -> a.getType().name()).toList());
    }

    // ---- the holdings file never fails the import

    private static ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow row(String isin, String symbol, String qty) {
        return new ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow(isin, symbol, qty == null ? null : new BigDecimal(qty));
    }

    @Test
    void invalidRowsAreDroppedAndTheValidOnesKept() {
        int kept = snapshotStore.replace(aliceId, broker, new ReconcileHoldingsSnapshot(today, java.util.Arrays.asList(
                row(childIsin, "CHD", "5"), row(null, " ", "5"), row("INE0NEG00001", "NEG", "-1"), row("INE0NULL0001", "NUL", null),
                row("INE0TOOLONGISIN0000000001", "LONG", "1"), row("INE0LONGSYM01", "S".repeat(61), "1"), null, row(null, "ONLYSYM", "0"))));

        assertEquals(2, kept);
        var rows = snapshotRepo.findOwned(aliceId);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> childIsin.equals(r.getIsin())));
        assertTrue(rows.stream().anyMatch(r -> "ONLYSYM".equals(r.getSymbol())));
    }

    @Test
    void aSnapshotDatedInTheFutureIsRefusedAndThePreviousOneStays() {
        snapshot("50");

        int kept = snapshotStore.replace(aliceId, broker, new ReconcileHoldingsSnapshot(today.plusDays(1), List.of(row(childIsin, "CHD", "1"))));

        assertEquals(0, kept);
        var rows = snapshotRepo.findOwned(aliceId);
        assertEquals(2, rows.size());
        assertEquals(today.minusDays(1), rows.get(0).getAsOfDate());
    }

    @Test
    void aSnapshotIsCappedAtFiveThousandRows() {
        List<ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow> many = new ArrayList<>();
        for (int i = 0; i < 5003; i++) {
            many.add(row("INE" + String.format("%09d", i), "S" + i, "1"));
        }

        int kept = snapshotStore.replace(aliceId, broker, new ReconcileHoldingsSnapshot(today, many));

        assertEquals(5000, kept);
        assertEquals(5000, snapshotRepo.findOwned(aliceId).size());
    }

    @Test
    void aFailingSnapshotWriteIsSwallowed() {
        // an unknown user breaks the foreign key at commit: the call still returns
        assertEquals(0, snapshotStore.replace(UUID.randomUUID(), broker, new ReconcileHoldingsSnapshot(today, List.of(row(childIsin, "CHD", "1")))));
    }

    @Test
    void anInvalidHoldingsFileDoesNotFailTheCommit() throws Exception {
        com.financeos.core.security.UserContext.setCurrentUserId(aliceId);
        try {
            var bad = new ReconcileHoldingsSnapshot(today.plusDays(5), List.of(row(childIsin, "CHD", "5")));

            var response = reconciliation.commit(new com.financeos.api.investment.dto.ReconcileCommitRequest(
                    com.financeos.domain.investment.reconcile.Broker.zerodha, broker, List.of(), List.of(), List.of(), bad));

            assertTrue(response != null);
            assertEquals(0, snapshotRepo.findOwned(aliceId).size());
        } finally {
            com.financeos.core.security.UserContext.clear();
        }
    }
}
