package com.financeos.api.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementParseRecordRepository;
import com.financeos.domain.statement.StatementParseRecords;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * A statement's parser version and a multi-KB parse snapshot round-trip through the real mapping: the version and time on
 * the statement, the snapshot gzipped and encrypted in statement_parse_records (stored bytes are not readable JSON, bound
 * to the statement id). Written on create and on a reconciling refresh, dropped when over-size, removed with the statement.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementParseRecordIntegrationTest {

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 2, 28);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementRepository statementRepository;
    @Autowired private StatementPersistenceService persistence;
    @Autowired private StatementParseRecords records;
    @Autowired private StatementParseRecordRepository recordRepository;

    private ApiTestClient api;
    private UUID userId;
    private UUID accountId;
    private Statement statement;

    @BeforeEach
    void setUp() throws Exception {
        String email = "parse-record-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        accountId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Record Savings", "last4", "5521",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        statement = persistence.createIfNew(user, account, StatementSource.file_upload, "feb.pdf", "sha-record",
                draft(4, snapshot("first"))).orElseThrow();
        records.saveQuietly(statement.getId(), 4, snapshot("first"));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    /** About 40 KB of JSON-looking text with quotes, non-ASCII and newlines, so a CLOB (not a VARCHAR) is really needed. */
    private static String snapshot(String tag) {
        StringBuilder sb = new StringBuilder("{\"tag\":\"").append(tag).append("\",\"transactions\":[");
        for (int i = 0; i < 400; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"index\":").append(i).append(",\"description\":\"UPI/₹ narration \\\"").append(i).append("\\\" café\\n\",\"amount\":\"12.50\"}");
        }
        return sb.append("]}").toString();
    }

    private static StatementDraft draft(Integer version, String snapshot) {
        return new StatementDraft("bank_account", START, END, new BigDecimal("1000"), new BigDecimal("1500"), "HDFC", "5521", 3, 0,
                new BigDecimal("200"), new BigDecimal("700"), "balance_chain", new BigDecimal("100"), true,
                StatementVerdict.AUTO_INGEST, null, null, Set.of(), version, snapshot);
    }

    private byte[] storedBytes(UUID statementId) {
        return jdbc.queryForObject("SELECT snapshot FROM statement_parse_records WHERE statement_id = ?", byte[].class, statementId.toString());
    }

    private Integer storedVersion() {
        return jdbc.queryForObject("SELECT parser_version FROM statements WHERE id = ?", Integer.class, statement.getId().toString());
    }

    private int recordRows(UUID statementId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM statement_parse_records WHERE statement_id = ?", Integer.class, statementId.toString());
    }

    @Test
    void aNewStatementRoundTripsVersionRecordedAtAndAMultiKbSnapshot() {
        String expected = snapshot("first");
        assertTrue(expected.length() > 30_000);

        assertEquals(4, storedVersion());
        Statement loaded = statementRepository.findById(statement.getId()).orElseThrow();
        assertEquals(4, loaded.getParserVersion());
        assertNotNull(loaded.getParseRecordedAt());
        assertEquals(expected, records.load(statement.getId()).orElseThrow());
        var row = recordRepository.findById(statement.getId()).orElseThrow();
        assertEquals(4, row.getParserVersion());
        assertEquals(expected.length(), row.getSnapshotChars());
    }

    @Test
    void theStoredBytesAreEncryptedNotReadableJsonOrPlainText() {
        byte[] stored = storedBytes(statement.getId());
        String asText = new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertTrue(!asText.contains("narration") && !asText.contains("\"tag\""));
        assertTrue(!(stored[0] == (byte) 0x1f && stored[1] == (byte) 0x8b), "not a bare gzip stream");
    }

    @Test
    void aRecordMovedToAnotherStatementIdFailsToLoad() {
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement other = persistence.createIfNew(user, account, StatementSource.file_upload, "mar.pdf", "sha-other",
                new StatementDraft("bank_account", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null, "HDFC", "5521",
                        1, 0, null, null, "heuristic", null, null, StatementVerdict.NEEDS_REVIEW, null)).orElseThrow();
        byte[] stolen = storedBytes(statement.getId());
        jdbc.update("INSERT INTO statement_parse_records (statement_id, parser_version, snapshot, snapshot_chars, recorded_at) VALUES (?, 1, ?, 10, CURRENT_TIMESTAMP)",
                other.getId().toString(), stolen);

        assertTrue(records.load(other.getId()).isEmpty());
        assertEquals(snapshot("first"), records.load(statement.getId()).orElseThrow());
    }

    @Test
    void garbageBytesLoadAsEmpty() {
        jdbc.update("UPDATE statement_parse_records SET snapshot = ? WHERE statement_id = ?", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28}, statement.getId().toString());

        assertTrue(records.load(statement.getId()).isEmpty());
    }

    @Test
    void aStatementWithoutARecordLoadsEmpty() {
        assertTrue(records.load(UUID.randomUUID()).isEmpty());
    }

    @Test
    void savingTheStatementEntityAgainDoesNotTouchItsRecord() {
        Statement loaded = statementRepository.findById(statement.getId()).orElseThrow();
        loaded.setBankName("Renamed Bank");
        statementRepository.save(loaded);

        assertEquals(snapshot("first"), records.load(statement.getId()).orElseThrow());
        assertEquals(4, storedVersion());
    }

    @Test
    void aReconcilingRefreshReplacesTheSnapshotAndVersionInPlace() {
        persistence.refreshReconciled(accountId, "sha-record", draft(5, snapshot("second")), List.of(), Set.of()).orElseThrow();
        records.saveQuietly(statement.getId(), 5, snapshot("second"));

        assertEquals(5, storedVersion());
        assertEquals(snapshot("second"), records.load(statement.getId()).orElseThrow());
        assertEquals(1, recordRows(statement.getId()));
    }

    @Test
    void anOversizeSnapshotOnARefreshRemovesTheOldRecordAndKeepsTheVersion() {
        String big = "x".repeat(2 * 1024 * 1024 + 1);
        persistence.refreshReconciled(accountId, "sha-record", draft(6, big), List.of(), Set.of()).orElseThrow();
        records.saveQuietly(statement.getId(), 6, big);

        assertEquals(6, storedVersion());
        assertEquals(0, recordRows(statement.getId()));
        assertTrue(records.load(statement.getId()).isEmpty());
    }

    @Test
    void aStatementCreatedWithoutARecordHasNoRecordAndNullColumns() {
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement plain = persistence.createIfNew(user, account, StatementSource.file_upload, "apr.pdf", "sha-plain",
                new StatementDraft("bank_account", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), null, null, "HDFC", "5521",
                        1, 0, null, null, "heuristic", null, null, StatementVerdict.NEEDS_REVIEW, null)).orElseThrow();

        assertNull(jdbc.queryForObject("SELECT parser_version FROM statements WHERE id = ?", Integer.class, plain.getId().toString()));
        assertNull(jdbc.queryForObject("SELECT parse_recorded_at FROM statements WHERE id = ?", java.sql.Timestamp.class, plain.getId().toString()));
        assertEquals(0, recordRows(plain.getId()));
    }

    @Test
    void deletingTheStatementThroughTheApiRemovesItsRecord() throws Exception {
        assertEquals(1, recordRows(statement.getId()));

        api.deleteJson("/api/v1/accounts/" + accountId + "/statements/" + statement.getId(), 200);

        assertEquals(0, recordRows(statement.getId()));
    }

    @Test
    void theStatementTransactionsThemselvesWriteNoRecord() {
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement s = persistence.createIfNew(user, account, StatementSource.file_upload, "may.pdf", "sha-may",
                draft(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), 2, snapshot("never"))).orElseThrow();

        assertEquals(2, jdbc.queryForObject("SELECT parser_version FROM statements WHERE id = ?", Integer.class, s.getId().toString()));
        assertEquals(0, recordRows(s.getId()));

        persistence.refreshReconciled(accountId, "sha-record", draft(7, snapshot("never")), List.of(), Set.of()).orElseThrow();
        assertEquals(7, storedVersion());
        assertEquals(snapshot("first"), records.load(statement.getId()).orElseThrow());
    }

    @Test
    void aNullSnapshotRemovesTheRecord() {
        records.saveQuietly(statement.getId(), 4, null);

        assertEquals(0, recordRows(statement.getId()));
    }

    @Test
    void aNullVersionWritesNothing() {
        records.saveQuietly(statement.getId(), null, snapshot("x"));

        assertEquals(snapshot("first"), records.load(statement.getId()).orElseThrow());
    }

    private static StatementDraft draft(LocalDate start, LocalDate end, Integer version, String snapshot) {
        return new StatementDraft("bank_account", start, end, null, null, "HDFC", "5521", 1, 0, null, null, "heuristic", null, null,
                StatementVerdict.NEEDS_REVIEW, null, null, Set.of(), version, snapshot);
    }
}
