package com.financeos.domain.statement.reparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.ingestion.StatementPasswordVault;
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

/**
 * Saving a statement password through the real vault publishes the event; after commit the user's locked
 * re-read failures become selectable again, nobody else's, and other failures stay parked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementReparseUnlockOnPasswordIntegrationTest {

    private static final int CURRENT = 3;
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementPersistenceService persistence;
    @Autowired private StatementRepository statementRepository;
    @Autowired private StatementFileStore fileStore;
    @Autowired private StatementPasswordVault vault;

    private UUID userA;
    private UUID userB;
    private UUID accountA;
    private UUID lockedA;
    private UUID lockedCapA;
    private UUID unreadableA;
    private UUID lockedB;
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        String emailA = "unlock-a-" + UUID.randomUUID() + "@example.test";
        String emailB = "unlock-b-" + UUID.randomUUID() + "@example.test";
        ApiTestClient apiA = ApiTestClient.signUp(mockMvc, mapper, emailA);
        ApiTestClient apiB = ApiTestClient.signUp(mockMvc, mapper, emailB);
        userA = userRepository.findByEmail(emailA).orElseThrow().getId();
        userB = userRepository.findByEmail(emailB).orElseThrow().getId();
        accountA = account(apiA);
        UUID accountB = account(apiB);
        UUID fileA = fileStore.store(userA, pdf(1), "a.pdf").orElseThrow();
        UUID fileB = fileStore.store(userB, pdf(2), "b.pdf").orElseThrow();
        lockedA = failed(userA, accountA, fileA, "LOCKED");
        lockedCapA = failed(userA, accountA, fileA, "LOCKED:cap");
        unreadableA = failed(userA, accountA, fileA, "UNREADABLE");
        lockedB = failed(userB, accountB, fileB, "LOCKED");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userA, userB));
    }

    @Test
    void savingAPasswordMakesTheUsersLockedFailuresSelectableAgainAndNobodyElses() {
        Set<UUID> before = picked();
        assertFalse(before.contains(lockedA));
        assertFalse(before.contains(lockedCapA));
        assertFalse(before.contains(lockedB));

        assertTrue(vault.save(userA, accountA, "pw-" + UUID.randomUUID()));

        Set<UUID> after = picked();
        assertTrue(after.contains(lockedA));
        assertTrue(after.contains(lockedCapA));
        assertFalse(after.contains(lockedB));
        assertEquals("PARSE_FAILED", outcome(lockedB));
    }

    @Test
    void savingAPasswordLeavesNonLockedFailuresParked() {
        assertTrue(vault.save(userA, accountA, "pw-" + UUID.randomUUID()));

        assertFalse(picked().contains(unreadableA));
        assertEquals("PARSE_FAILED", outcome(unreadableA));
        assertEquals("UNREADABLE", jdbc.queryForObject("SELECT reparse_summary FROM statements WHERE id = ?", String.class, unreadableA.toString()));
    }

    @Test
    void aPasswordThatChangesNothingDoesNotUnlock() {
        String pw = "pw-" + UUID.randomUUID();
        assertTrue(vault.save(userA, accountA, pw));
        UUID again = failed(userA, accountA, fileOf(lockedA), "LOCKED");

        assertFalse(vault.save(userA, accountA, pw));

        assertFalse(picked().contains(again));
    }

    private Set<UUID> picked() {
        List<StatementRepository.ReparseCandidate> all = statementRepository.findReparseCandidates(
                1000, CURRENT, NOW.minus(Duration.ofHours(24)), 3, 0);
        Set<String> users = Set.of(userA.toString(), userB.toString());
        return all.stream().filter(c -> users.contains(c.getUserId())).map(c -> UUID.fromString(c.getId()))
                .collect(java.util.stream.Collectors.toSet());
    }

    private UUID fileOf(UUID statement) {
        return UUID.fromString(jdbc.queryForObject("SELECT file_id FROM statements WHERE id = ?", String.class, statement.toString()));
    }

    private String outcome(UUID id) {
        return jdbc.queryForObject("SELECT reparse_outcome FROM statements WHERE id = ?", String.class, id.toString());
    }

    /** A statement with a stored file, an older parse, stamped PARSE_FAILED at the current version. */
    private UUID failed(UUID userId, UUID accountId, UUID fileId, String summary) {
        seq++;
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        UUID id = persistence.createIfNew(user, account, StatementSource.file_upload, null, "sha-" + UUID.randomUUID(),
                draft(LocalDate.of(2020, 1, 1).plusMonths(seq), LocalDate.of(2020, 1, 28).plusMonths(seq))).orElseThrow().getId();
        jdbc.update("UPDATE statements SET file_id = ?, parser_version = 1, reparse_version = ?, reparse_outcome = 'PARSE_FAILED', "
                        + "reparse_summary = ?, reparse_at = ?, reparse_attempts = 3 WHERE id = ?",
                fileId.toString(), CURRENT, summary, OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(100)), ZoneOffset.UTC), id.toString());
        return id;
    }

    private UUID account(ApiTestClient api) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Unlock Savings", "last4", "7483",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static byte[] pdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
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
