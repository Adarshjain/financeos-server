package com.financeos.api.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.file.StatementFileStore;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The re-read job's native selection and guarded stamps against real persistence: a statement is selected only with a
 * stored file and a stored parse older than the current parser version that was not examined at that version yet;
 * the stamps write only their own columns and never lower a version.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementReparseSelectionIntegrationTest {

    private static final int CURRENT = 3;
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant BASE = Instant.parse("2000-01-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementPersistenceService persistence;
    @Autowired private StatementRepository statementRepository;
    @Autowired private StatementFileStore fileStore;
    @Autowired private PlatformTransactionManager txManager;

    private UUID userA;
    private UUID userB;
    private Account accountA;
    private Account accountB;
    private UUID file;
    private int seq;

    private UUID noFile, fileCurrent, fileAhead, neverExamined, nullVersion, examinedCurrent, examinedOlder, examinedNewer, b1, b2;

    @BeforeEach
    void setUp() throws Exception {
        String emailA = "reparse-a-" + UUID.randomUUID() + "@example.test";
        String emailB = "reparse-b-" + UUID.randomUUID() + "@example.test";
        ApiTestClient apiA = ApiTestClient.signUp(mockMvc, mapper, emailA);
        ApiTestClient apiB = ApiTestClient.signUp(mockMvc, mapper, emailB);
        userA = userRepository.findByEmail(emailA).orElseThrow().getId();
        userB = userRepository.findByEmail(emailB).orElseThrow().getId();
        accountA = accountRepository.findById(account(apiA)).orElseThrow();
        accountB = accountRepository.findById(account(apiB)).orElseThrow();
        User ua = userRepository.findById(userA).orElseThrow();
        User ub = userRepository.findById(userB).orElseThrow();
        file = fileStore.store(userA, pdf(), "a.pdf").orElseThrow();
        UUID fileB = fileStore.store(userB, pdf2(), "b.pdf").orElseThrow();

        //                     user account  file     parser  reparse  created
        noFile          = stmt(ua, accountA, null,    1,      null,    1);
        fileCurrent     = stmt(ua, accountA, file,    CURRENT, null,   2);  // parsed by the current parser already
        fileAhead       = stmt(ua, accountA, file,    CURRENT + 1, null, 3); // parsed by a newer one
        neverExamined   = stmt(ua, accountA, file,    1,      null,    4);  // eligible
        nullVersion     = stmt(ua, accountA, file,    null,   null,    5);  // eligible: no version recorded at all
        examinedCurrent = stmt(ua, accountA, file,    1,      CURRENT, 6);  // examined at this version
        examinedOlder   = stmt(ua, accountA, file,    1,      CURRENT - 1, 7); // examined at an older version: eligible
        examinedNewer   = stmt(ua, accountA, file,    1,      CURRENT + 1, 8); // examined at a newer version
        b1 = stmt(ub, accountB, fileB, 2, null, 20);
        b2 = stmt(ub, accountB, fileB, 2, null, 21);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userA, userB));
    }

    @Test
    void selectsOnlyStatementsWithAFileAndAnOlderParseNotYetExaminedAtThisVersion() {
        List<UUID> picked = mine(find(1000, CURRENT));

        // interleaved per user, oldest first: A's oldest eligible, B's oldest, A's next, B's next, ...
        assertEquals(List.of(neverExamined, b1, nullVersion, b2, examinedOlder), picked);
    }

    @Test
    void aStatementWithoutAFileIsNeverSelectedHoweverOldItsParse() {
        assertFalse(mine(find(1000, CURRENT + 10)).contains(noFile));
    }

    @Test
    void raisingTheVersionSelectsAStatementExaminedAtTheOldOneAgain() {
        assertFalse(mine(find(1000, CURRENT)).contains(examinedCurrent));

        List<UUID> next = mine(find(1000, CURRENT + 1));

        assertTrue(next.contains(examinedCurrent));
        assertTrue(next.contains(fileCurrent));
        assertFalse(next.contains(examinedNewer));
    }

    @Test
    void aStatementParsedAtTheCurrentVersionIsNotSelected() {
        List<UUID> picked = mine(find(1000, CURRENT));

        assertFalse(picked.contains(fileCurrent));
        assertFalse(picked.contains(fileAhead));
    }

    @Test
    void theBatchLimitApplies() {
        assertEquals(List.of(neverExamined, b1), mine(find(2, CURRENT)));
    }

    @Test
    void markReparseWritesItsFourColumnsAndNothingElseAndTheStatementIsNotSelectedAgain() {
        int rows = tx(() -> statementRepository.markReparse(neverExamined, CURRENT, NOW, "WOULD_CHANGE", "fields=1 [period] addRows=0 extras=0"));

        assertEquals(1, rows);
        Map<String, Object> row = jdbc.queryForMap("SELECT reparse_version, reparse_at, reparse_outcome, reparse_summary, parser_version, parse_recorded_at, file_id FROM statements WHERE id = ?", neverExamined.toString());
        assertEquals(CURRENT, ((Number) row.get("reparse_version")).intValue());
        assertEquals(NOW, toInstant(row.get("reparse_at")));
        assertEquals("WOULD_CHANGE", row.get("reparse_outcome"));
        assertEquals("fields=1 [period] addRows=0 extras=0", row.get("reparse_summary"));
        assertEquals(1, ((Number) row.get("parser_version")).intValue());
        assertNull(row.get("parse_recorded_at"));
        assertNotNull(row.get("file_id"));
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));
        assertNull(jdbc.queryForObject("SELECT reparse_outcome FROM statements WHERE id = ?", String.class, nullVersion.toString()));
    }

    @Test
    void markReparseNeverOverwritesAStampAtTheSameOrALaterVersion() {
        assertEquals(0, tx(() -> statementRepository.markReparse(examinedCurrent, CURRENT, NOW, "ERROR", null)));
        assertEquals(0, tx(() -> statementRepository.markReparse(examinedNewer, CURRENT, NOW, "ERROR", null)));
        assertEquals(CURRENT, versionOf("reparse_version", examinedCurrent));
        assertEquals(CURRENT + 1, versionOf("reparse_version", examinedNewer));
    }

    @Test
    void markReparseMayReplaceAnOlderVersionsStamp() {
        assertEquals(1, tx(() -> statementRepository.markReparse(examinedOlder, CURRENT, NOW, "SAME", null)));
        assertEquals(CURRENT, versionOf("reparse_version", examinedOlder));
    }

    @Test
    void markParsedAtVersionRaisesTheParserVersionAndNeverLowersIt() {
        assertEquals(1, tx(() -> statementRepository.markParsedAtVersion(neverExamined, CURRENT, NOW)));
        assertEquals(CURRENT, versionOf("parser_version", neverExamined));
        assertEquals(NOW, toInstant(jdbc.queryForMap("SELECT parse_recorded_at FROM statements WHERE id = ?", neverExamined.toString()).get("parse_recorded_at")));

        assertEquals(0, tx(() -> statementRepository.markParsedAtVersion(fileAhead, CURRENT, NOW)));
        assertEquals(CURRENT + 1, versionOf("parser_version", fileAhead));
        assertEquals(1, tx(() -> statementRepository.markParsedAtVersion(nullVersion, CURRENT, NOW)));
    }

    @Test
    void markParsedAtVersionLeavesTheReparseColumnsAlone() {
        tx(() -> statementRepository.markParsedAtVersion(neverExamined, CURRENT, NOW));

        Map<String, Object> row = jdbc.queryForMap("SELECT reparse_version, reparse_at, reparse_outcome FROM statements WHERE id = ?", neverExamined.toString());
        assertNull(row.get("reparse_version"));
        assertNull(row.get("reparse_at"));
        assertNull(row.get("reparse_outcome"));
    }

    private List<StatementRepository.ReparseCandidate> find(int batch, int current) {
        return find(batch, current, 0);
    }

    private List<StatementRepository.ReparseCandidate> find(int batch, int current, int applyOn) {
        return statementRepository.findReparseCandidates(batch, current, NOW.minus(java.time.Duration.ofHours(24)), 3, applyOn);
    }

    private void examinedAt(UUID id, String outcome, Instant at, Integer attempts) {
        jdbc.update("UPDATE statements SET reparse_version = ?, reparse_outcome = ?, reparse_at = ?, reparse_attempts = ? WHERE id = ?",
                CURRENT, outcome, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), attempts, id.toString());
    }

    @Test
    void anErrorIsRetriedOnlyAfter24HoursAndAtMostThreeAttemptsPerVersion() {
        examinedAt(neverExamined, "ERROR", NOW.minus(java.time.Duration.ofHours(2)), 1);
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));

        examinedAt(neverExamined, "ERROR", NOW.minus(java.time.Duration.ofHours(25)), 1);
        assertTrue(mine(find(1000, CURRENT)).contains(neverExamined));

        examinedAt(neverExamined, "ERROR", NOW.minus(java.time.Duration.ofHours(25)), 3);
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void aStaleInProgressIsRetriedLikeAnErrorButAFreshOneIsNot() {
        examinedAt(neverExamined, "IN_PROGRESS", NOW.minus(java.time.Duration.ofMinutes(5)), 1);
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));

        examinedAt(neverExamined, "IN_PROGRESS", NOW.minus(java.time.Duration.ofHours(30)), 2);
        assertTrue(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void parkedAndSettledOutcomesAreNeverRetried() {
        for (String outcome : List.of("FILE_MISSING", "SAME", "WOULD_CLEAR", "NOT_RECONCILED", "REFRESHED", "APPLY_SKIPPED", "NO_TARGET", "PARSE_FAILED")) {
            examinedAt(neverExamined, outcome, NOW.minus(java.time.Duration.ofHours(100)), 1);
            assertFalse(mine(find(1000, CURRENT, 1)).contains(neverExamined), outcome);
        }
    }

    private void parkedFailure(UUID id, String summary, Instant at, Integer attempts) {
        examinedAt(id, "PARSE_FAILED", at, attempts);
        jdbc.update("UPDATE statements SET reparse_summary = ? WHERE id = ?", summary, id.toString());
    }

    @Test
    void aLockedParseFailureIsRetriedOnlyAfter24Hours() {
        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(2)), 1);
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));

        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(25)), 1);
        assertTrue(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void aCappedLockedParseFailureIsRetriedToo() {
        parkedFailure(neverExamined, "LOCKED:cap", NOW.minus(java.time.Duration.ofHours(25)), 1);

        assertTrue(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void aLockedParseFailureIsRetriedAtMostThreeTimesPerVersion() {
        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(25)), 2);
        assertTrue(mine(find(1000, CURRENT)).contains(neverExamined));

        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(25)), 3);
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void aLockedParseFailureWithNoAttemptCountIsRetried() {
        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(25)), null);

        assertTrue(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void aLockedFailureAttemptsStartOverWhenTheParserVersionRises() {
        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(25)), 3);
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));

        // a newer parser version: not yet examined at it, so selected regardless of the old attempts
        assertTrue(mine(find(1000, CURRENT + 1)).contains(neverExamined));
        assertEquals(1, tx(() -> statementRepository.markReparseStarted(neverExamined, CURRENT + 1, NOW)));
        assertEquals(1, versionOf("reparse_attempts", neverExamined));
    }

    @Test
    void otherParseFailuresAreNeverRetried() {
        for (String summary : List.of("UNREADABLE", "UNKNOWN", "UNSUPPORTED_TYPE", "EMPTY_FILE", "TOO_MANY_PAGES", "OTHER", "NOT LOCKED")) {
            parkedFailure(neverExamined, summary, NOW.minus(java.time.Duration.ofHours(100)), 1);
            assertFalse(mine(find(1000, CURRENT, 1)).contains(neverExamined), summary);
        }
    }

    @Test
    void aLockedFailureIsNotRetriedOnceTheStatementsParseIsCurrent() {
        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(25)), 1);
        jdbc.update("UPDATE statements SET parser_version = ? WHERE id = ?", CURRENT, neverExamined.toString());

        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void theRetriedLockedFailureIsSettledByTheNextStamp() {
        parkedFailure(neverExamined, "LOCKED", NOW.minus(java.time.Duration.ofHours(25)), 1);

        assertEquals(1, tx(() -> statementRepository.markReparseStarted(neverExamined, CURRENT, NOW)));
        assertEquals(2, versionOf("reparse_attempts", neverExamined));
        assertEquals(1, tx(() -> statementRepository.markReparse(neverExamined, CURRENT, NOW, "SAME", null)));
        assertEquals("SAME", outcome(neverExamined));
        assertFalse(mine(find(1000, CURRENT)).contains(neverExamined));
    }

    @Test
    void aRecordedWouldChangeIsSelectedOnlyWhenApplyIsOn() {
        examinedAt(neverExamined, "WOULD_CHANGE", NOW.minus(java.time.Duration.ofMinutes(1)), 1);

        assertFalse(mine(find(1000, CURRENT, 0)).contains(neverExamined));
        assertTrue(mine(find(1000, CURRENT, 1)).contains(neverExamined));
    }

    @Test
    void anAttemptStampCountsAttemptsAtTheVersionAndResetsWhenTheVersionRises() {
        assertEquals(1, tx(() -> statementRepository.markReparseStarted(neverExamined, CURRENT, NOW)));
        assertEquals(1, versionOf("reparse_attempts", neverExamined));
        assertEquals("IN_PROGRESS", outcome(neverExamined));
        tx(() -> statementRepository.markReparseStarted(neverExamined, CURRENT, NOW));
        assertEquals(2, versionOf("reparse_attempts", neverExamined));

        assertEquals(1, tx(() -> statementRepository.markReparseStarted(neverExamined, CURRENT + 1, NOW)));
        assertEquals(1, versionOf("reparse_attempts", neverExamined));
        assertEquals(0, tx(() -> statementRepository.markReparseStarted(neverExamined, CURRENT, NOW)));
    }

    @Test
    void theOutcomeStampSettlesInProgressAndWouldChangeButNotASettledOutcome() {
        tx(() -> statementRepository.markReparseStarted(neverExamined, CURRENT, NOW));
        assertEquals(1, tx(() -> statementRepository.markReparse(neverExamined, CURRENT, NOW, "WOULD_CHANGE", "x")));
        assertEquals(1, tx(() -> statementRepository.markReparse(neverExamined, CURRENT, NOW, "REFRESHED", "y")));
        assertEquals(0, tx(() -> statementRepository.markReparse(neverExamined, CURRENT, NOW, "ERROR", null)));
        assertEquals("REFRESHED", outcome(neverExamined));
    }

    private String outcome(UUID id) {
        return jdbc.queryForObject("SELECT reparse_outcome FROM statements WHERE id = ?", String.class, id.toString());
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(s -> work.get());
    }

    private int versionOf(String column, UUID id) {
        return jdbc.queryForObject("SELECT " + column + " FROM statements WHERE id = ?", Integer.class, id.toString());
    }

    private static Instant toInstant(Object ts) {
        if (ts instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (ts instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        return ((java.time.ZonedDateTime) ts).toInstant();
    }

    // ---- one-time re-examination of gmail WOULD_CHANGE recorded before the watermark rule ----

    private void recorded(UUID id, String source, String outcome, String summary) {
        jdbc.update("UPDATE statements SET source = ?, reparse_version = ?, reparse_outcome = ?, reparse_summary = ?, reparse_at = ?, reparse_attempts = 1 WHERE id = ?",
                source, CURRENT, outcome, summary, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), id.toString());
    }

    @Test
    void aGmailWouldChangeWithRowsToAddAndNoDroppedMarkIsSelectedAgainWithApplyOff() {
        recorded(neverExamined, "gmail", "WOULD_CHANGE", "changed=[] cleared=[] addRows=17 extras=0 relinked=0");

        List<StatementRepository.ReparseCandidate> picked = find(1000, CURRENT, 0);

        assertTrue(mine(picked).contains(neverExamined));
        StatementRepository.ReparseCandidate c = picked.stream().filter(x -> neverExamined.toString().equals(x.getId())).findFirst().orElseThrow();
        assertEquals("gmail", c.getSource());
        assertEquals("WOULD_CHANGE", c.getReparseOutcome());
        assertEquals("changed=[] cleared=[] addRows=17 extras=0 relinked=0", c.getReparseSummary());
    }

    @Test
    void aGmailWouldChangeAlreadyCarryingDroppedIsNotSelectedAgainWithApplyOff() {
        recorded(neverExamined, "gmail", "WOULD_CHANGE", "changed=[] cleared=[] addRows=17 extras=0 relinked=0 dropped=0");
        recorded(examinedOlder, "gmail", "WOULD_CHANGE", "changed=[] cleared=[] addRows=2 extras=0 relinked=0 dropped=5");

        List<UUID> picked = mine(find(1000, CURRENT, 0));

        assertFalse(picked.contains(neverExamined));
        assertFalse(picked.contains(examinedOlder));
    }

    @Test
    void aGmailWouldChangeWithNoRowsToAddIsNotReexamined() {
        recorded(neverExamined, "gmail", "WOULD_CHANGE", "changed=[period] cleared=[] addRows=0 extras=1 relinked=0");

        assertFalse(mine(find(1000, CURRENT, 0)).contains(neverExamined));
    }

    @Test
    void aGmailWouldChangeWithoutAnAddRowsFigureIsNotReexamined() {
        recorded(neverExamined, "gmail", "WOULD_CHANGE", null);

        assertFalse(mine(find(1000, CURRENT, 0)).contains(neverExamined));
    }

    @Test
    void anUploadWouldChangeWithRowsToAddIsNotReexamined() {
        recorded(neverExamined, "file_upload", "WOULD_CHANGE", "changed=[] cleared=[] addRows=17 extras=0 relinked=0");

        assertFalse(mine(find(1000, CURRENT, 0)).contains(neverExamined));
    }

    @Test
    void aGmailStatementWithAnotherOutcomeIsNotReexamined() {
        for (String outcome : List.of("SAME", "WOULD_CLEAR", "APPLY_SKIPPED", "REFRESHED", "NO_TARGET")) {
            recorded(neverExamined, "gmail", outcome, "changed=[] cleared=[] addRows=17 extras=0 relinked=0");
            assertFalse(mine(find(1000, CURRENT, 0)).contains(neverExamined), outcome);
        }
    }

    @Test
    void aGmailWouldChangeStampedAtAnOtherVersionIsNotPickedByTheReexaminationClause() {
        recorded(examinedNewer, "gmail", "WOULD_CHANGE", "changed=[] cleared=[] addRows=17 extras=0 relinked=0");
        jdbc.update("UPDATE statements SET reparse_version = ? WHERE id = ?", CURRENT + 1, examinedNewer.toString());

        assertFalse(mine(find(1000, CURRENT, 0)).contains(examinedNewer));
    }

    @Test
    void theReexaminationStampLeavesTheStatementSettledAfterTheJobStampsAnythingWithDropped() {
        recorded(neverExamined, "gmail", "WOULD_CHANGE", "changed=[] cleared=[] addRows=17 extras=0 relinked=0");
        assertTrue(mine(find(1000, CURRENT, 0)).contains(neverExamined));

        tx(() -> statementRepository.markReparse(neverExamined, CURRENT, NOW, "WOULD_CHANGE", "changed=[] cleared=[] addRows=3 extras=0 relinked=0 dropped=0"));

        assertFalse(mine(find(1000, CURRENT, 0)).contains(neverExamined));
    }

    /** The picked ids of these two users only (other tests' leftovers are ignored), in the order returned. */
    private List<UUID> mine(List<StatementRepository.ReparseCandidate> candidates) {
        Set<String> users = Set.of(userA.toString(), userB.toString());
        return candidates.stream().filter(c -> users.contains(c.getUserId())).map(c -> UUID.fromString(c.getId())).toList();
    }

    private UUID stmt(User user, Account account, UUID fileId, Integer parserVersion, Integer reparseVersion, int n) {
        seq++;
        UUID id = persistence.createIfNew(user, account, StatementSource.file_upload, null, "sha-" + seq,
                draft(LocalDate.of(2020, 1, 1).plusMonths(seq), LocalDate.of(2020, 1, 28).plusMonths(seq))).orElseThrow().getId();
        jdbc.update("UPDATE statements SET created_at = ?, file_id = ?, parser_version = ?, reparse_version = ? WHERE id = ?",
                OffsetDateTime.ofInstant(BASE.plusSeconds(n), ZoneOffset.UTC), fileId == null ? null : fileId.toString(),
                parserVersion, reparseVersion, id.toString());
        return id;
    }

    private UUID account(ApiTestClient api) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Reparse Savings", "last4", "7483",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] pdf2() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static StatementDraft draft(LocalDate start, LocalDate end) {
        return new StatementDraft("bank_account", start, end, BigDecimal.ZERO, new BigDecimal("100.00"), "HSBC",
                "001-234567-006", 3, 0, new BigDecimal("10.00"), new BigDecimal("110.00"), "balance_chain",
                new BigDecimal("100.0"), true, StatementVerdict.AUTO_INGEST, Map.of());
    }
}
