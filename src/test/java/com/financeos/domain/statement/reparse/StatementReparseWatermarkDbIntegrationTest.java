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
import static org.junit.jupiter.api.Assertions.assertNull;
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
class StatementReparseWatermarkDbIntegrationTest {

    private static final int V = 3;
    private static final LocalDate D0 = LocalDate.of(2026, 1, 3);
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

    /** One stored link: the row's date and amount, its line index and its balance. */
    private record Link(LocalDate date, String amount, int index, String balance) {}

    /** Stores a statement of this source with its original file and one transaction linked per {@code links} entry. */
    private void seed(StatementSource source, StatementDraft stored, Link... links) {
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        UUID file = fileStore.store(userId, pdf, "a.pdf").orElseThrow();
        Statement s = persistence.createIfNew(user, account, source, "msg-1",
                StatementPersistenceService.sha256Hex(pdf), stored).orElseThrow();
        statementId = s.getId();
        jdbc.update("UPDATE statements SET file_id = ?, parser_version = 1 WHERE id = ?", file.toString(), statementId.toString());
        List<StatementPersistenceService.TxnLink> out = new ArrayList<>();
        for (Link l : links) {
            out.add(new StatementPersistenceService.TxnLink(txn(user, account, l.date(), l.amount()), l.index(), new BigDecimal(l.balance()), true));
        }
        persistence.linkTransactions(statementId, out);
    }

    private void watermark(LocalDate date) {
        jdbc.update("UPDATE accounts SET ingest_from_date = ? WHERE id = ?", java.sql.Date.valueOf(date), accountId.toString());
    }

    private void recordedWouldChange(String summary) {
        jdbc.update("UPDATE statements SET reparse_version = ?, reparse_outcome = 'WOULD_CHANGE', reparse_summary = ?, reparse_at = ?, reparse_attempts = 1 WHERE id = ?",
                V, summary, java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(3600)), statementId.toString());
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

    private static final StatementDraft SAME_DRAFT = draft(card("100", "5000"), new BigDecimal("55"), 2);
    private static final String OLD_SUMMARY = "changed=[] cleared=[] addRows=2 extras=0 relinked=0";

    private String summary() {
        return (String) reparseColumns().get("reparse_summary");
    }

    private Set<String> linkedIndexes() {
        Set<String> out = new java.util.TreeSet<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT line_index, balance_after FROM statement_transactions WHERE statement_id = ? ORDER BY line_index", statementId.toString())) {
            out.add(r.get("line_index") + ":" + ((BigDecimal) r.get("balance_after")).stripTrailingZeros().toPlainString());
        }
        return out;
    }

    // ---- gmail statement: links only to post-watermark rows, file also holds pre-watermark lines ----

    @Test
    void aGmailStatementLinkedOnlyToPostWatermarkRowsIsSameWhileTheFileAlsoHoldsPreWatermarkLines() {
        watermark(D2);
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"), new Link(D3, "30", 1, "3"));
        reads(SAME_DRAFT, List.of(line(D0, "5", "0"), line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        Map<String, Object> before = state();

        job(false).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
        assertNull(reparseColumns().get("reparse_summary"));
        assertEquals(V, ((Number) reparseColumns().get("parser_version")).intValue());
        assertEquals(before.get("transactions"), state().get("transactions"));
        assertEquals(before.get("links"), state().get("links"));
    }

    @Test
    void withApplyOnAGmailStatementWithPreWatermarkLinesInTheFileCreatesNoPreWatermarkTransactions() {
        watermark(D2);
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"), new Link(D3, "30", 1, "3"));
        reads(SAME_DRAFT, List.of(line(D0, "5", "0"), line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        int rowsBefore = transactionCount();

        job(true).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
        assertEquals(rowsBefore, transactionCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ? AND transaction_date < ?", Integer.class,
                accountId.toString(), java.sql.Date.valueOf(D2)));
    }

    @Test
    void aGmailStatementWithAMissingPostWatermarkRowStillReportsItAndApplyCreatesOnlyThatRow() {
        watermark(D2);
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"));
        reads(SAME_DRAFT, List.of(line(D0, "5", "0"), line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        int rowsBefore = transactionCount();

        job(false).run();

        assertEquals("WOULD_CHANGE", reparseColumns().get("reparse_outcome"));
        assertEquals("changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=2", summary());
        assertEquals(rowsBefore, transactionCount());
    }

    @Test
    void withApplyOnAMissingPostWatermarkRowIsCreatedAndNoPreWatermarkRowIs() {
        watermark(D2);
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"));
        reads(SAME_DRAFT, List.of(line(D0, "5", "0"), line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        int rowsBefore = transactionCount();

        job(true).run();

        assertEquals("REFRESHED", reparseColumns().get("reparse_outcome"));
        assertEquals(rowsBefore + 1, transactionCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ? AND transaction_date < ?", Integer.class,
                accountId.toString(), java.sql.Date.valueOf(D2)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ? AND sourced_description = 'line 30'", Integer.class, accountId.toString()));
    }

    // ---- every line before the watermark ----

    @Test
    void aGmailStatementWhoseLinesAreAllBeforeTheWatermarkAndHasNoLinksIsSame() {
        watermark(LocalDate.of(2026, 2, 1));
        seed(StatementSource.gmail, SAME_DRAFT);
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D2, "20", "2")));
        Map<String, Object> before = state();

        job(false).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
        assertNull(summary());
        assertEquals(before.get("transactions"), state().get("transactions"));
        assertEquals(before.get("links"), state().get("links"));
    }

    @Test
    void withApplyOnAGmailStatementWhoseLinesAreAllBeforeTheWatermarkCreatesNothing() {
        watermark(LocalDate.of(2026, 2, 1));
        seed(StatementSource.gmail, SAME_DRAFT);
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D2, "20", "2")));

        job(true).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
        assertEquals(0, transactionCount());
    }

    // ---- no watermark ----

    @Test
    void aGmailStatementOnAnAccountWithNoWatermarkIsNotFiltered() {
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"));
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D2, "20", "2")));

        job(false).run();

        assertEquals("WOULD_CHANGE", reparseColumns().get("reparse_outcome"));
        assertEquals("changed=[] cleared=[] addRows=1 extras=0 relinked=1 dropped=0", summary());
    }

    // ---- watermark moved earlier after ingest ----

    @Test
    void aWatermarkMovedEarlierShowsTheLinesNowInsideTheWindowAsRowsToAddAndApplyCreatesThem() {
        watermark(D2); // ingested with this watermark: only the two later rows exist
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"), new Link(D3, "30", 1, "3"));
        watermark(D0); // moved earlier since
        reads(SAME_DRAFT, List.of(line(D0, "5", "0"), line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        int rowsBefore = transactionCount();

        job(false).run();

        assertEquals("WOULD_CHANGE", reparseColumns().get("reparse_outcome"));
        assertEquals("changed=[] cleared=[] addRows=2 extras=0 relinked=2 dropped=0", summary());
        assertEquals(rowsBefore, transactionCount());

        job(true).run(); // selected again by the apply switch

        assertEquals("REFRESHED", reparseColumns().get("reparse_outcome"));
        assertEquals(rowsBefore + 2, transactionCount());
    }

    // ---- watermark moved later after ingest ----

    @Test
    void aWatermarkMovedLaterIsSameBecauseTheComparedLinesAreExactlyWhatWasImported() {
        watermark(D0); // ingested with this watermark: all three rows linked
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D1, "10", 0, "1"), new Link(D2, "20", 1, "2"), new Link(D3, "30", 2, "3"));
        watermark(D2); // moved later since: the D1 row is now before it
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        Map<String, Object> before = state();

        job(false).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
        assertNull(summary());
        assertEquals(before.get("transactions"), state().get("transactions"));
        assertEquals(before.get("links"), state().get("links"));
    }

    @Test
    void withApplyOnAWatermarkMovedLaterIsSameAndTouchesNothing() {
        watermark(D0);
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D1, "10", 0, "1"), new Link(D2, "20", 1, "2"), new Link(D3, "30", 2, "3"));
        watermark(D2);
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        Map<String, Object> before = state();

        job(true).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
        assertEquals(before.get("transactions"), state().get("transactions"));
        assertEquals(before.get("links"), state().get("links"));
    }

    // ---- a linked row dated before its own line pulls the cutoff before the watermark ----

    @Test
    void aLinkedRowDatedTwoDaysBeforeItsLineAndBeforeTheWatermarkIsReportedAndApplyCreatesNothingBeforeTheWatermark() {
        watermark(D2); // 6 Jan
        // The D3 line is linked to a row dated 4 Jan (alert / edited date); the 5 Jan line was dropped at ingest.
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2.minusDays(2), "30", 0, "3"));
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D3, "30", "3")));
        int rowsBefore = transactionCount();

        job(false).run();

        assertEquals("WOULD_CHANGE", reparseColumns().get("reparse_outcome"));
        assertEquals("changed=[] cleared=[] addRows=1 extras=0 relinked=1 dropped=0 preWatermark=1", summary());

        job(true).run();

        assertEquals("APPLY_SKIPPED", reparseColumns().get("reparse_outcome"));
        assertEquals(rowsBefore, transactionCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE account_id = ? AND transaction_date < ?", Integer.class,
                accountId.toString(), java.sql.Date.valueOf(D2)), "only the seeded row is before the watermark");
    }

    // ---- uploads ----

    @Test
    void anUploadStatementIsNotFilteredEvenWhenTheAccountHasAWatermark() {
        watermark(D2);
        seed(StatementSource.file_upload, SAME_DRAFT, new Link(D1, "10", 0, "1"), new Link(D2, "20", 1, "2"));
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D2, "20", "2")));

        job(false).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
    }

    @Test
    void anUploadStatementMissingAPreWatermarkRowStillReportsItAndApplyCreatesIt() {
        watermark(D2);
        seed(StatementSource.file_upload, SAME_DRAFT, new Link(D2, "20", 0, "2"));
        reads(SAME_DRAFT, List.of(line(D1, "10", "1"), line(D2, "20", "2")));
        int rowsBefore = transactionCount();

        job(false).run();
        assertEquals("changed=[] cleared=[] addRows=1 extras=0 relinked=1", summary());

        job(true).run();
        assertEquals(rowsBefore + 1, transactionCount());
    }

    // ---- one-time re-examination of recorded WOULD_CHANGE ----

    @Test
    void aRecordedGmailWouldChangeCausedByTheBugIsReexaminedOnceAndSettlesAsSame() {
        watermark(D2);
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"), new Link(D3, "30", 1, "3"));
        reads(SAME_DRAFT, List.of(line(D0, "5", "0"), line(D1, "10", "1"), line(D2, "20", "2"), line(D3, "30", "3")));
        recordedWouldChange(OLD_SUMMARY);

        job(false).run();

        assertEquals("SAME", reparseColumns().get("reparse_outcome"));
        verify(opener, times(1)).open(any(), any(), any(), any(), any(), anyBoolean());

        job(false).run();
        job(false).run();

        verify(opener, times(1)).open(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aRecordedGmailWouldChangeThatReallyDiffersIsRewrittenWithDroppedZeroAndNeverReexaminedAgain() {
        seed(StatementSource.gmail, SAME_DRAFT, new Link(D2, "20", 0, "2"));
        reads(SAME_DRAFT, List.of(line(D2, "20", "2"), line(D3, "30", "3")));
        recordedWouldChange(OLD_SUMMARY);

        job(false).run();

        assertEquals("WOULD_CHANGE", reparseColumns().get("reparse_outcome"));
        assertEquals("changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=0", summary());
        verify(opener, times(1)).open(any(), any(), any(), any(), any(), anyBoolean());

        job(false).run();
        job(false).run();

        verify(opener, times(1)).open(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals("changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=0", summary());
    }

    @Test
    void aRecordedUploadWouldChangeIsNotReexamined() {
        seed(StatementSource.file_upload, SAME_DRAFT, new Link(D2, "20", 0, "2"));
        reads(SAME_DRAFT, List.of(line(D2, "20", "2"), line(D3, "30", "3")));
        recordedWouldChange(OLD_SUMMARY);

        job(false).run();

        verify(opener, times(0)).open(any(), any(), any(), any(), any(), anyBoolean());
        assertEquals(OLD_SUMMARY, summary());
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
