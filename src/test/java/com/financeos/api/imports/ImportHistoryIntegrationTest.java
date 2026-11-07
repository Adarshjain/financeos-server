package com.financeos.api.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** GET /api/v1/imports: every kind and status, newest first, paged across the union, filtered by kind, per user. */
@SpringBootTest
@AutoConfigureMockMvc
class ImportHistoryIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private final Instant base = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
    private final List<UUID> users = new ArrayList<>();
    private ApiTestClient api;
    private UUID userId;
    private UUID bank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "imports-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        users.add(userId);
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main Bank", "last4", "1111",
                "bank", "hdfc", "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, users);
    }

    private Timestamp at(int minutes) {
        return Timestamp.from(base.plus(minutes, ChronoUnit.MINUTES));
    }

    private void statement(UUID user, UUID account, String source, String ref, int count, int minute) {
        jdbc.update("INSERT INTO statements (id, user_id, account_id, source, source_ref, transaction_count, period_start, period_end, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, DATE '2026-01-01', DATE '2026-01-31', ?, ?)",
                UUID.randomUUID().toString(), user.toString(), account.toString(), source, ref, count, at(minute), at(minute));
    }

    private UUID job(UUID user, String type, String status, String payload, String error, int minute) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO jobs (id, user_id, type, status, trigger_source, payload, error_message, attempt, cancel_requested, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, 'USER', ?, ?, 0, FALSE, ?, ?)",
                id.toString(), user.toString(), type, status, payload, error, at(minute), at(minute));
        return id;
    }

    private void artifact(UUID jobId, String filename, int minute) {
        jdbc.update("INSERT INTO job_artifacts (id, job_id, filename, size_bytes, data, created_at, retained) VALUES (?, ?, ?, 1, ?, ?, FALSE)",
                UUID.randomUUID().toString(), jobId.toString(), filename, new byte[] {1}, at(minute));
    }

    private void held(UUID user, String fileName, UUID jobId, UUID artifactId, String reason, boolean resolved, int minute) {
        jdbc.update("INSERT INTO held_uploads (id, user_id, job_id, artifact_id, file_index, file_name, reason, created_at, resolved_at) "
                + "VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), user.toString(), jobId.toString(), artifactId.toString(), fileName, reason,
                at(minute), resolved ? at(minute + 1) : null);
    }

    private UUID npsAccount(UUID user, String holder) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO nps_account (id, user_id, pran_encrypted, pran_last4, holder_name, relation, cra, tier2_active, status, read_gmail, created_at, updated_at) "
                + "VALUES (?, ?, 'x', '1234', ?, 'SELF', 'PROTEAN', FALSE, 'ACTIVE', TRUE, ?, ?)",
                id.toString(), user.toString(), holder, at(0), at(0));
        return id;
    }

    private void npsStatement(UUID user, UUID account, String source, int minute) {
        jdbc.update("INSERT INTO nps_statement (id, user_id, account_id, tier, period_start, period_end, source, units_mismatch, incomplete, imported_at) "
                + "VALUES (?, ?, ?, 'I', DATE '2026-04-01', DATE '2026-09-30', ?, FALSE, FALSE, ?)",
                UUID.randomUUID().toString(), user.toString(), account.toString(), source, at(minute));
    }

    private UUID employer(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO epf_employer (id, user_id, name, created_at, updated_at) VALUES (?, ?, 'Acme', ?, ?)",
                id.toString(), user.toString(), at(0), at(0));
        return id;
    }

    private void epfEntry(UUID user, UUID employer, String source, String creditedOn, Integer importedMinute) {
        jdbc.update("INSERT INTO epf_entry (id, user_id, employer_id, type, credited_on, employee_amount, employer_amount, pension_amount, source, imported_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'CONTRIBUTION', DATE '" + creditedOn + "', 100, 100, 0, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), user.toString(), employer.toString(), source,
                importedMinute == null ? null : at(importedMinute), at(0), at(0));
    }

    private JsonNode get(String query) throws Exception {
        return api.getJson("/api/v1/imports" + query, 200);
    }

    private static List<String> field(JsonNode page, String name) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get(name).isNull() ? null : n.get(name).asText()));
        return out;
    }

    /** One of everything, minutes 10 (oldest) to 80 (newest). */
    private void seedEverything() {
        statement(userId, bank, "file_upload", "hdfc-jan.pdf", 42, 10);
        statement(userId, bank, "gmail", "gmail-msg-1", 7, 20);
        UUID failedJob = job(userId, "STATEMENT_INGEST", "FAILED", "{\"accountId\":\"" + bank + "\"}", "Could not read the file", 30);
        artifact(failedJob, "broken.pdf", 30);
        UUID heldJob = job(userId, "STATEMENT_INGEST", "SUCCEEDED", null, null, 35);
        UUID heldArtifact = UUID.randomUUID();
        artifact(heldJob, "held.pdf", 35);
        held(userId, "held.pdf", heldJob, heldArtifact, "NO_MATCH", false, 40);
        held(userId, "resolved.pdf", heldJob, heldArtifact, "NO_MATCH", true, 41);
        npsStatement(userId, npsAccount(userId, "Asha"), "UPLOAD", 50);
        UUID emp = employer(userId);
        epfEntry(userId, emp, "PASSBOOK", "2026-01-31", 60);
        epfEntry(userId, emp, "PASSBOOK", "2026-02-28", 60);
        epfEntry(userId, emp, "MANUAL", "2026-03-31", null);
        UUID okBroker = job(userId, "INVESTMENT_IMPORT_COMMIT", "SUCCEEDED", null, null, 70);
        artifact(okBroker, "tradebook.csv", 70);
        job(userId, "BROKER_RECONCILE_COMMIT", "FAILED", null, "Bad row", 80);
        job(userId, "INVESTMENT_IMPORT_COMMIT", "RUNNING", null, null, 85);
        job(userId, "RULE_APPLY", "FAILED", null, "not an import", 86);
    }

    @Test
    void everyKindAndStatusAppearsNewestFirstWithItsFields() throws Exception {
        seedEverything();

        JsonNode page = get("");

        assertEquals(8, page.get("totalElements").asInt());
        assertEquals(List.of("broker", "broker", "epf_passbook", "nps_statement", "statement", "statement", "statement", "statement"),
                field(page, "kind"));
        assertEquals(List.of("failed", "done", "done", "done", "held", "failed", "done", "done"), field(page, "status"));
        JsonNode content = page.get("content");
        assertEquals("Bad row", content.get(0).get("errorMessage").asText());
        assertEquals("tradebook.csv", content.get(1).get("fileName").asText());
        JsonNode epf = content.get(2);
        assertEquals(2, epf.get("count").asInt(), "the manual entry is not a passbook batch");
        assertEquals("2026-01-31", epf.get("periodStart").asText());
        assertEquals("2026-02-28", epf.get("periodEnd").asText());
        assertEquals("upload", epf.get("source").asText());
        assertTrue(epf.get("id").asText().startsWith("epf:"));
        JsonNode nps = content.get(3);
        assertEquals("Asha", nps.get("accountName").asText());
        assertEquals("2026-04-01", nps.get("periodStart").asText());
        JsonNode held = content.get(4);
        assertEquals("held.pdf", held.get("fileName").asText());
        assertEquals("No account matched this file", held.get("errorMessage").asText());
        JsonNode failed = content.get(5);
        assertEquals("broken.pdf", failed.get("fileName").asText());
        assertEquals("Could not read the file", failed.get("errorMessage").asText());
        assertEquals(bank.toString(), failed.get("accountId").asText());
        assertEquals("Main Bank", failed.get("accountName").asText());
        JsonNode gmail = content.get(6);
        assertEquals("gmail", gmail.get("source").asText());
        assertNull(gmail.get("fileName").isNull() ? null : gmail.get("fileName").asText());
        assertEquals(7, gmail.get("count").asInt());
        JsonNode upload = content.get(7);
        assertEquals("upload", upload.get("source").asText());
        assertEquals("hdfc-jan.pdf", upload.get("fileName").asText());
        assertEquals(bank.toString(), upload.get("accountId").asText());
        assertEquals("Main Bank", upload.get("accountName").asText());
        assertEquals(42, upload.get("count").asInt());
        assertEquals("2026-01-01", upload.get("periodStart").asText());
        assertEquals("done", upload.get("status").asText());
    }

    @Test
    void pagingWalksTheWholeUnionWithoutGapsOrRepeats() throws Exception {
        seedEverything();
        List<String> all = field(get(""), "id");

        List<String> paged = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            JsonNode page = get("?page=" + p + "&size=3");
            assertEquals(8, page.get("totalElements").asInt());
            assertEquals(3, page.get("totalPages").asInt());
            paged.addAll(field(page, "id"));
        }

        assertEquals(all, paged);
        assertEquals(8, paged.stream().distinct().count());
    }

    @Test
    void theKindFilterNarrowsRowsAndTotal() throws Exception {
        seedEverything();

        assertEquals(4, get("?kind=statement").get("totalElements").asInt());
        assertEquals(List.of("held", "failed", "done", "done"), field(get("?kind=statement"), "status"));
        assertEquals(1, get("?kind=nps_statement").get("totalElements").asInt());
        assertEquals(1, get("?kind=epf_passbook").get("totalElements").asInt());
        JsonNode broker = get("?kind=broker&size=1&page=1");
        assertEquals(2, broker.get("totalElements").asInt());
        assertEquals(List.of("broker"), field(broker, "kind"));
        assertEquals(400, api.get("/api/v1/imports?kind=nonsense").getStatus());
    }

    @Test
    void anotherUsersRowsNeverAppear() throws Exception {
        String otherEmail = "imports-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        UUID otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        users.add(otherId);
        UUID otherBank = other.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Other Bank", "last4", "9999",
                "bank", "hdfc", "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        statement(otherId, otherBank, "file_upload", "theirs.pdf", 1, 5);
        job(otherId, "STATEMENT_INGEST", "FAILED", "{\"accountId\":\"" + otherBank + "\"}", "theirs", 6);
        job(otherId, "INVESTMENT_IMPORT_COMMIT", "SUCCEEDED", null, null, 7);
        npsStatement(otherId, npsAccount(otherId, "Them"), "UPLOAD", 8);
        epfEntry(otherId, employer(otherId), "PASSBOOK", "2026-01-31", 9);
        UUID otherJob = job(otherId, "STATEMENT_INGEST", "SUCCEEDED", null, null, 10);
        held(otherId, "theirs-held.pdf", otherJob, UUID.randomUUID(), "LOCKED", false, 11);
        statement(userId, bank, "file_upload", "mine.pdf", 3, 12);

        assertEquals(List.of("mine.pdf"), field(get(""), "fileName"));
        assertEquals(1, get("").get("totalElements").asInt());
        assertEquals(6, other.getJson("/api/v1/imports", 200).get("totalElements").asInt());
    }

    @Test
    void anEmptyHistoryIsAnEmptyPage() throws Exception {
        JsonNode page = get("");
        assertEquals(0, page.get("totalElements").asInt());
        assertEquals(0, page.get("content").size());
    }
}
