package com.financeos.api.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import java.time.Duration;
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
 * The original-file back-fill's native selection and bulk outcome write against real persistence: exactly which
 * statements are picked (never tried, or retryable and past the window), in which order (interleaved per user), and
 * that the batch limit applies.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementFileBackfillSelectionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofHours(24));
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
    private int seq;

    private UUID a1, a2, a3, a4, a5, a6, a7, a8, a9, b1, b2, b3;

    @BeforeEach
    void setUp() throws Exception {
        String emailA = "backfill-a-" + UUID.randomUUID() + "@example.test";
        String emailB = "backfill-b-" + UUID.randomUUID() + "@example.test";
        ApiTestClient apiA = ApiTestClient.signUp(mockMvc, mapper, emailA);
        ApiTestClient apiB = ApiTestClient.signUp(mockMvc, mapper, emailB);
        userA = userRepository.findByEmail(emailA).orElseThrow().getId();
        userB = userRepository.findByEmail(emailB).orElseThrow().getId();
        accountA = accountRepository.findById(account(apiA)).orElseThrow();
        accountB = accountRepository.findById(account(apiB)).orElseThrow();
        User ua = userRepository.findById(userA).orElseThrow();
        User ub = userRepository.findById(userB).orElseThrow();
        UUID file = fileStore.store(userA, pdf(), "a.pdf").orElseThrow();

        // User A, created order a1 < a2 < ... ; user B created after all of A.
        a1 = stmt(ua, accountA, StatementSource.gmail, "m", "sha", 1, null, null, null);                    // never tried: eligible
        a2 = stmt(ua, accountA, StatementSource.gmail, "m", "sha", 2, NOW.minusSeconds(5), "MAIL_GONE", null); // final
        a3 = stmt(ua, accountA, StatementSource.gmail, "m", "sha", 3, NOW.minus(Duration.ofHours(2)), "ERROR", null); // retryable, inside window
        a4 = stmt(ua, accountA, StatementSource.gmail, "m", "sha", 4, NOW.minus(Duration.ofHours(48)), "NO_CONNECTION", null); // past window
        a5 = stmt(ua, accountA, StatementSource.file_upload, null, "sha", 5, null, null, null);              // upload
        a6 = stmt(ua, accountA, StatementSource.gmail, "m", "sha", 6, null, null, file);                     // has a file
        a7 = stmt(ua, accountA, StatementSource.gmail, null, "sha", 7, null, null, null);                    // null source_ref
        a8 = stmt(ua, accountA, StatementSource.gmail, "m", null, 8, null, null, null);                      // null sha
        a9 = stmt(ua, accountA, StatementSource.gmail, "m", "sha", 9, null, null, null);                     // never tried
        b1 = stmt(ub, accountB, StatementSource.gmail, "m", "sha", 20, null, null, null);
        b2 = stmt(ub, accountB, StatementSource.gmail, "m", "sha", 21, NOW.minus(Duration.ofHours(48)), "ERROR", null);
        b3 = stmt(ub, accountB, StatementSource.gmail, "m", "sha", 22, null, null, null);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userA, userB));
    }

    @Test
    void selectsOnlyEligibleStatementsInterleavedAcrossUsers() {
        List<UUID> picked = mine(statementRepository.findFileBackfillCandidates(1000, CUTOFF));

        // rank 1 of each user (a1, b1), rank 2 (a4, b2), rank 3 (a9, b3); plain created order would put all of A first.
        assertEquals(List.of(a1, b1, a4, b2, a9, b3), picked);
    }

    @Test
    void aRetryableRowIsSelectedOnlyOnceItsAttemptIsOlderThanTheWindow() {
        assertEquals(Set.of(a4, b2), mine(statementRepository.findFileBackfillCandidates(1000, CUTOFF)).stream()
                .filter(id -> id.equals(a4) || id.equals(b2) || id.equals(a3)).collect(Collectors.toSet()));
        // Move the window back by 3 hours: a3 (tried 2h ago) is still inside it; forward by 3 hours its attempt is older.
        List<UUID> later = mine(statementRepository.findFileBackfillCandidates(1000, NOW.minus(Duration.ofHours(1))));
        assertEquals(true, later.contains(a3));
        assertEquals(false, mine(statementRepository.findFileBackfillCandidates(1000, NOW.minus(Duration.ofHours(3)))).contains(a3));
    }

    @Test
    void aFinalOutcomeIsNeverSelectedHoweverOld() {
        List<UUID> far = mine(statementRepository.findFileBackfillCandidates(1000, NOW.plus(Duration.ofDays(3650))));

        assertEquals(false, far.contains(a2));
    }

    @Test
    void theBatchLimitApplies() {
        List<UUID> picked = mine(statementRepository.findFileBackfillCandidates(2, CUTOFF));

        assertEquals(List.of(a1, b1), picked);
    }

    @Test
    void markFileBackfillWritesBothColumnsAndNothingElse() {
        int rows = mark(a1, "ATTACHED");

        assertEquals(1, rows);
        Map<String, Object> row = jdbc.queryForMap("SELECT file_backfill_at, file_backfill_outcome, file_id FROM statements WHERE id = ?", a1.toString());
        assertEquals("ATTACHED", row.get("file_backfill_outcome"));
        assertEquals(NOW, toInstant(row.get("file_backfill_at")));
        assertNull(row.get("file_id"));
        Map<String, Object> untouched = jdbc.queryForMap("SELECT file_backfill_at, file_backfill_outcome FROM statements WHERE id = ?", a9.toString());
        assertNull(untouched.get("file_backfill_at"));
        assertNull(untouched.get("file_backfill_outcome"));
    }

    @Test
    void markFileBackfillNeverOverwritesAFinalOutcome() {
        int rows = mark(a2, "ERROR");

        assertEquals(0, rows);
        assertEquals("MAIL_GONE", outcomeOf(a2));
    }

    @Test
    void markFileBackfillMayReplaceARetryableOutcome() {
        assertEquals(1, mark(a3, "ATTACHED"));
        assertEquals("ATTACHED", outcomeOf(a3));
        assertEquals(1, mark(a4, "STORE_UNAVAILABLE"));
        assertEquals("STORE_UNAVAILABLE", outcomeOf(a4));
    }

    @Test
    void aStoreUnavailableRowIsRetriedOnlyAfterTheWindow() {
        mark(a1, "STORE_UNAVAILABLE");
        jdbc.update("UPDATE statements SET file_backfill_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC), a1.toString());
        assertEquals(false, mine(statementRepository.findFileBackfillCandidates(1000, CUTOFF)).contains(a1));

        jdbc.update("UPDATE statements SET file_backfill_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(48)), ZoneOffset.UTC), a1.toString());
        assertEquals(true, mine(statementRepository.findFileBackfillCandidates(1000, CUTOFF)).contains(a1));
    }

    @Test
    void findFileIdByIdIsEmptyForANullColumnAndPresentOtherwise() {
        assertEquals(java.util.Optional.empty(), statementRepository.findFileIdById(a1));
        assertEquals(true, statementRepository.findFileIdById(a6).isPresent());
    }

    private int mark(UUID id, String outcome) {
        return new TransactionTemplate(txManager).execute(s -> statementRepository.markFileBackfill(id, NOW, outcome));
    }

    private String outcomeOf(UUID id) {
        return jdbc.queryForObject("SELECT file_backfill_outcome FROM statements WHERE id = ?", String.class, id.toString());
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

    /** The picked ids of these two users only (other tests' leftovers are ignored), in the order returned. */
    private List<UUID> mine(List<StatementRepository.FileBackfillCandidate> candidates) {
        Set<String> users = Set.of(userA.toString(), userB.toString());
        return candidates.stream().filter(c -> users.contains(c.getUserId())).map(c -> UUID.fromString(c.getId())).toList();
    }

    private UUID stmt(User user, Account account, StatementSource source, String ref, String sha, int n, Instant triedAt,
                      String outcome, UUID fileId) {
        seq++;
        UUID id = persistence.createIfNew(user, account, source, ref == null ? null : "msg-" + seq, "sha-" + seq,
                draft(LocalDate.of(2020, 1, 1).plusMonths(seq), LocalDate.of(2020, 1, 28).plusMonths(seq))).orElseThrow().getId();
        jdbc.update("UPDATE statements SET source_ref = ?, file_sha256 = ?, created_at = ?, file_backfill_at = ?, "
                        + "file_backfill_outcome = ?, file_id = ? WHERE id = ?",
                ref == null ? null : "msg-" + seq, sha == null ? null : "sha-" + seq,
                OffsetDateTime.ofInstant(BASE.plusSeconds(n), ZoneOffset.UTC),
                triedAt == null ? null : OffsetDateTime.ofInstant(triedAt, ZoneOffset.UTC), outcome,
                fileId == null ? null : fileId.toString(), id.toString());
        return id;
    }

    private UUID account(ApiTestClient api) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Backfill Savings", "last4", "7483",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
