package com.financeos.domain.statement.reparse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserFilterEnabler;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.ingestion.ReconciledRefresh;
import com.financeos.domain.ingestion.StatementOpener;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementParseRecords;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.file.StatementFileStore;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The re-read job over real persistence: the real {@code refreshReconciled}, {@code ReconciledRefresh}, batch matcher,
 * parse records and repositories. Only the file opener is a stub (it returns a prepared parse), so what differs between
 * the stored statement and the "re-read" is controlled.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementReparseDbIntegrationTest {

    private static final int V = 3;
    private static final LocalDate D1 = LocalDate.of(2026, 1, 5);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 6);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 7);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementPersistenceService persistence;
    @Autowired private StatementRepository statementRepository;
    @Autowired private StatementFileStore fileStore;
    @Autowired private ReconciledRefresh reconciledRefresh;
    @Autowired private StatementParseRecords parseRecords;
    @Autowired private UserFilterEnabler filter;
    @Autowired private PlatformTransactionManager txManager;

    private UUID userId;
    private UUID accountId;
    private UUID statementId;
    private StatementOpener opener;
    private final byte[] pdf = pdf();

    @BeforeEach
    void setUp() throws Exception {
        String email = "reparse-db-" + UUID.randomUUID() + "@example.test";
        ApiTestClient api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        accountId = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Reparse Card", "last4", "8421",
                "creditLimit", 200000, "anniversaryDate", "2026-01-15", "financialPosition", "liability",
                "excludeFromNetAsset", false));
        opener = mock(StatementOpener.class);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    // ---- seeding ----

    private static Map<String, Object> card(String totalDue, String creditLimit) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("total_amount_due", new BigDecimal(totalDue));
        m.put("minimum_amount_due", new BigDecimal("10"));
        m.put("payment_due_date", LocalDate.of(2026, 2, 10));
        if (creditLimit != null) {
            m.put("credit_limit", new BigDecimal(creditLimit));
        }
        return m;
    }

    private static StatementDraft draft(Map<String, Object> cardFields, BigDecimal totalCredits, int count) {
        return new StatementDraft("credit_card", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), BigDecimal.ZERO,
                new BigDecimal("60"), "HDFC", "xx8421", count, 0, new BigDecimal("60"), totalCredits, "heuristic",
                new BigDecimal("100"), true, StatementVerdict.AUTO_INGEST, cardFields, true, Set.of(), 3, "{\"read\":\"v3\"}");
    }

    /** Stores the statement with two linked rows (balance of the first link: 1) and its original file. */
    private void seed(StatementDraft stored) {
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        UUID file = fileStore.store(userId, pdf, "a.pdf").orElseThrow();
        Statement s = persistence.createIfNew(user, account, StatementSource.file_upload, "a.pdf",
                StatementPersistenceService.sha256Hex(pdf), stored).orElseThrow();
        statementId = s.getId();
        jdbc.update("UPDATE statements SET file_id = ?, parser_version = 1 WHERE id = ?", file.toString(), statementId.toString());
        UUID t1 = txn(user, account, D1, "10");
        UUID t2 = txn(user, account, D2, "20");
        persistence.linkTransactions(statementId, List.of(
                new StatementPersistenceService.TxnLink(t1, 0, new BigDecimal("1"), true),
                new StatementPersistenceService.TxnLink(t2, 1, new BigDecimal("2"), true)));
    }

    private UUID txn(User user, Account account, LocalDate date, String amount) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.file_upload);
        t.setSourcedDescription("seed " + amount);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t).getId();
    }

    private static ParsedStatementLine line(LocalDate d, String amount, String balance) {
        return new ParsedStatementLine(d, new BigDecimal(amount), "DEBIT", "line " + amount, new BigDecimal(balance), true);
    }

    /** What the opener "reads": these lines under this draft. */
    private void reads(StatementDraft draft, List<ParsedStatementLine> lines) {
        StatementExtractionResult r = new StatementExtractionResult(true, lines, "xx8421", draft.periodStart(),
                draft.periodEnd(), draft, null, "text");
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(new StatementOpener.Opened(r, false));
    }

    private StatementReparse job(boolean apply) {
        // Only this test's user is visible to the job: the selection is global and other tests leave statements behind.
        StatementRepository mine = (StatementRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{StatementRepository.class}, (proxy, m, args) -> {
                    try {
                        Object r = m.invoke(statementRepository, args);
                        if (m.getName().equals("findReparseCandidates")) {
                            List<StatementRepository.ReparseCandidate> out = new ArrayList<>();
                            for (Object c : (List<?>) r) {
                                StatementRepository.ReparseCandidate rc = (StatementRepository.ReparseCandidate) c;
                                if (userId.toString().equals(rc.getUserId())) out.add(rc);
                            }
                            return out;
                        }
                        return r;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new StatementReparse(mine, fileStore, accountRepository, userRepository, opener, notFoundFinder(), persistence,
                reconciledRefresh, parseRecords, filter, new SimpleMeterRegistry(), txManager, Clock.systemUTC(), V,
                true, apply, 50, 60);
    }

    private static com.financeos.domain.ingestion.StatementPasswordFinder notFoundFinder() {
        var f = mock(com.financeos.domain.ingestion.StatementPasswordFinder.class);
        when(f.find(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new com.financeos.domain.ingestion.StatementPasswordFinder.Found(null, null, false));
        return f;
    }

    // ---- reading the database back ----

    private List<Map<String, Object>> rows(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> copy = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            copy.putAll(r);
            copy.keySet().removeIf(k -> k.toLowerCase().startsWith("reparse_"));
            out.add(copy);
        }
        return out;
    }

    /** Everything the job could touch, except the reparse_* columns. */
    private Map<String, Object> state() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("statement", rows("SELECT * FROM statements WHERE id = ?", statementId.toString()));
        m.put("card", rows("SELECT * FROM statement_credit_card_details WHERE statement_id = ?", statementId.toString()));
        m.put("links", rows("SELECT * FROM statement_transactions WHERE statement_id = ? ORDER BY transaction_id", statementId.toString()));
        m.put("transactions", rows("SELECT * FROM transactions WHERE account_id = ? ORDER BY id", accountId.toString()));
        m.put("parseRecords", rows("SELECT statement_id, parser_version FROM statement_parse_records WHERE statement_id = ?", statementId.toString()));
        return m;
    }

    private Map<String, Object> reparseColumns() {
        return jdbc.queryForMap("SELECT reparse_version, reparse_outcome, reparse_summary, reparse_attempts, parser_version, parse_recorded_at FROM statements WHERE id = ?",
                statementId.toString());
    }

    private int transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ?", Integer.class, accountId.toString());
    }

    // ---- (a) apply=false changes nothing ----

    @Test
    void withApplyOffADifferingStatementIsLeftByteForByteAsItWasAndOnlyTheReparseColumnsAreWritten() {
        seed(draft(card("100", "5000"), new BigDecimal("55"), 2));
        // The re-read: another header field, a card figure changed and one dropped, a third line missing, and the first link's balance differs.
        reads(draft(card("120", null), null, 3),
                List.of(line(D1, "10", "9"), line(D2, "20", "2"), line(D3, "30", "3")));
        Map<String, Object> before = state();
        int rowsBefore = transactionCount();

        job(false).run();

        assertEquals(before, state(), "statement, card details, links, transactions and parse records are untouched");
        assertEquals(rowsBefore, transactionCount(), "no new transactions");
        Map<String, Object> r = reparseColumns();
        assertEquals("WOULD_CHANGE", r.get("reparse_outcome"));
        assertEquals(V, ((Number) r.get("reparse_version")).intValue());
        String summary = (String) r.get("reparse_summary");
        assertTrue(summary.contains("addRows=1") && summary.contains("relinked=1"), summary);
        assertTrue(summary.contains("total_amount_due") && summary.contains("cleared=[total_credits,credit_limit]"), summary);
        assertEquals(1, ((Number) r.get("parser_version")).intValue(), "the stored parser version is not raised");
    }

    // ---- (b) SAME ----

    @Test
    void anIdenticalReReadStampsTheParserVersionAndWritesTheParseRecord() {
        StatementDraft same = draft(card("100", "5000"), new BigDecimal("55"), 2);
        seed(same);
        reads(same, List.of(line(D1, "10", "1"), line(D2, "20", "2")));
        Map<String, Object> before = state();

        job(false).run();

        Map<String, Object> r = reparseColumns();
        assertEquals("SAME", r.get("reparse_outcome"));
        assertEquals(V, ((Number) r.get("parser_version")).intValue());
        assertNotNull(r.get("parse_recorded_at"));
        assertEquals(1, ((Number) jdbc.queryForObject("SELECT COUNT(*) FROM statement_parse_records WHERE statement_id = ?",
                Integer.class, statementId.toString())).intValue());
        assertEquals(3, ((Number) jdbc.queryForObject("SELECT parser_version FROM statement_parse_records WHERE statement_id = ?",
                Integer.class, statementId.toString())).intValue());
        // Besides the version stamp and the record, the data is as it was.
        @SuppressWarnings("unchecked") Map<String, Object> afterStatement = ((List<Map<String, Object>>) state().get("statement")).get(0);
        @SuppressWarnings("unchecked") Map<String, Object> beforeStatement = ((List<Map<String, Object>>) before.get("statement")).get(0);
        afterStatement.remove("parser_version");
        afterStatement.remove("parse_recorded_at");
        beforeStatement.remove("parser_version");
        beforeStatement.remove("parse_recorded_at");
        assertEquals(beforeStatement, afterStatement);
        assertEquals(before.get("links"), state().get("links"));
        assertEquals(before.get("transactions"), state().get("transactions"));
    }

    @Test
    void aLinkWhoseBalanceDiffersIsNotSame() {
        StatementDraft same = draft(card("100", "5000"), new BigDecimal("55"), 2);
        seed(same);
        reads(same, List.of(line(D1, "10", "99"), line(D2, "20", "2")));

        job(false).run();

        Map<String, Object> r = reparseColumns();
        assertEquals("WOULD_CHANGE", r.get("reparse_outcome"));
        assertEquals("changed=[] cleared=[] addRows=0 extras=0 relinked=1", r.get("reparse_summary"));
        assertEquals(1, ((Number) r.get("parser_version")).intValue());
    }

    // ---- (c) apply=true keeps cleared values ----

    @Test
    void withApplyOnTheChangedValueIsAppliedAndBothClearedValuesKeepTheirStoredValues() {
        seed(draft(card("100", "5000"), new BigDecimal("55"), 2));
        reads(draft(card("120", null), null, 3),
                List.of(line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        int rowsBefore = transactionCount();

        job(true).run();

        Map<String, Object> r = reparseColumns();
        assertEquals("REFRESHED", r.get("reparse_outcome"));
        Map<String, Object> card = jdbc.queryForMap("SELECT total_amount_due, credit_limit FROM statement_credit_card_details WHERE statement_id = ?", statementId.toString());
        assertEquals(0, new BigDecimal("120").compareTo((BigDecimal) card.get("total_amount_due")), "changed value applied");
        assertEquals(0, new BigDecimal("5000").compareTo((BigDecimal) card.get("credit_limit")), "cleared card value kept");
        BigDecimal credits = jdbc.queryForObject("SELECT total_credits FROM statements WHERE id = ?", BigDecimal.class, statementId.toString());
        assertEquals(0, new BigDecimal("55").compareTo(credits), "cleared statement-level value kept");
        assertEquals(3, jdbc.queryForObject("SELECT transaction_count FROM statements WHERE id = ?", Integer.class, statementId.toString()));
        assertEquals(rowsBefore + 1, transactionCount(), "the missing line became one row");
        assertEquals(1, ((Number) jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ? AND source = 'file_upload' AND sourced_description = 'line 30'",
                Integer.class, accountId.toString())).intValue());
        assertEquals(V, ((Number) r.get("parser_version")).intValue());
        assertEquals(1, ((Number) jdbc.queryForObject("SELECT COUNT(*) FROM statement_parse_records WHERE statement_id = ?",
                Integer.class, statementId.toString())).intValue());
    }

    @Test
    void withApplyOnAStatementWhoseOnlyDifferenceIsAClearedFieldIsNotAppliedAndIsWouldClear() {
        seed(draft(card("100", "5000"), new BigDecimal("55"), 2));
        reads(draft(card("100", null), new BigDecimal("55"), 2), List.of(line(D1, "10", "1"), line(D2, "20", "2")));
        Map<String, Object> before = state();

        job(true).run();

        assertEquals(before, state());
        assertEquals("WOULD_CLEAR", reparseColumns().get("reparse_outcome"));
        assertEquals("changed=[] cleared=[credit_limit] addRows=0 extras=0 relinked=0", reparseColumns().get("reparse_summary"));
    }

    // ---- (d) recorded WOULD_CHANGE is applied once the switch is on, and does not loop ----

    @Test
    void turningApplyOnAppliesARecordedWouldChangeAtTheSameVersionOnceAndNeverLoops() {
        seed(draft(card("100", "5000"), new BigDecimal("55"), 2));
        reads(draft(card("120", "5000"), new BigDecimal("55"), 2), List.of(line(D1, "10", "1"), line(D2, "20", "2")));

        job(false).run();
        assertEquals("WOULD_CHANGE", reparseColumns().get("reparse_outcome"));
        job(false).run();
        verify(opener, times(1)).open(any(), any(), any(), any(), any(), anyBoolean()); // not selected again with apply off

        job(true).run();
        assertEquals("REFRESHED", reparseColumns().get("reparse_outcome"));
        verify(opener, times(2)).open(any(), any(), any(), any(), any(), anyBoolean());
        BigDecimal due = jdbc.queryForObject("SELECT total_amount_due FROM statement_credit_card_details WHERE statement_id = ?", BigDecimal.class, statementId.toString());
        assertEquals(0, new BigDecimal("120").compareTo(due));

        job(true).run();
        job(true).run();
        verify(opener, times(2)).open(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals("REFRESHED", reparseColumns().get("reparse_outcome"));
    }

    private static byte[] pdf() {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
