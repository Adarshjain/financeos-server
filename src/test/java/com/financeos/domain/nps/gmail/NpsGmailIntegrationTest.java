package com.financeos.domain.nps.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContextHelper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.collect.NpsGmailInboxCollector;
import com.financeos.domain.instrument.price.NpsTrustFeedClient;
import com.financeos.domain.nps.NpsAccount;
import com.financeos.domain.nps.NpsAccountRepository;
import com.financeos.domain.nps.NpsCatalogService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.ingest.GmailDocumentHandler;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** NPS from Gmail over real persistence: statement mails, passwords, new accounts, alerts and their chain. */
@SpringBootTest
@AutoConfigureMockMvc
class NpsGmailIntegrationTest {

    private static final String BASE = "/api/v1/nps";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String PRAN = "110012345678";
    private static final String POP_E = "SM008001";
    private static final String POP_C = "SM008002";
    private static final String POP_G = "SM008003";
    private static final List<String> CODES = List.of(POP_E, POP_C, POP_G);
    private static final String ENPS = "eNPS-alerts@proteantech.in";
    private static final String CREDIT_SENDER = "nps-alerts@proteantech.in";
    private static final String STATEMENT_SENDER = "nps-statements@mailer.proteantech.in";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired NpsCatalogService catalogService;
    @Autowired NpsGmailHandler handler;
    @Autowired NpsAccountRepository accountRepository;
    @Autowired GmailConnectionRepository connectionRepository;
    @Autowired NpsGmailInboxCollector collector;
    @Autowired NpsGmailPendingRepository pendingRepository;
    @MockBean GmailEngine gmailEngine;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private GmailConnection connection;
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        String email = "nps-gmail-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String other = "nps-gmail-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, other);
        otherUserId = userRepository.findByEmail(other).orElseThrow().getId();
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 11, 5).atTime(10, 0).atZone(IST).toInstant(), IST));
        seedCatalog();
        GmailConnection c = new GmailConnection();
        c.setUser(userRepository.getReferenceById(userId));
        c.setEmail("owner@example.test");
        c.setEncryptedRefreshToken("token");
        connection = connectionRepository.saveAndFlush(c);
    }

    @AfterEach
    void cleanUp() {
        AppTime.reset();
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    @AfterAll
    static void dropCatalog(@Autowired JdbcTemplate jdbc) {
        String in = "('" + String.join("','", CODES) + "')";
        jdbc.update("DELETE FROM instrument_prices WHERE instrument_id IN (SELECT instrument_id FROM nps_scheme WHERE scheme_code IN " + in + ")");
        List<String> instrumentIds = jdbc.queryForList("SELECT instrument_id FROM nps_scheme WHERE scheme_code IN " + in, String.class);
        jdbc.update("DELETE FROM nps_scheme WHERE scheme_code IN " + in);
        instrumentIds.forEach(id -> jdbc.update("DELETE FROM instruments WHERE id = ?", id));
    }

    /** The POP schemes of the wide fixtures: NAVs for 14 and 15 Jan 2025. */
    private void seedCatalog() {
        String pfm = "HDFC Pension Management Company Limited";
        String suffix = "HDFC PENSION MANAGEMENT COMPANY LIMITED SCHEME ";
        for (LocalDate day : List.of(LocalDate.of(2025, 1, 14), LocalDate.of(2025, 1, 15))) {
            boolean fifteenth = day.getDayOfMonth() == 15;
            List<NpsTrustFeedClient.NavRow> rows = new ArrayList<>();
            rows.add(new NpsTrustFeedClient.NavRow(POP_E, suffix + "E - TIER I", pfm, day, new BigDecimal(fifteenth ? "62.5000" : "62.4417")));
            rows.add(new NpsTrustFeedClient.NavRow(POP_C, suffix + "C - TIER I", pfm, day, new BigDecimal(fifteenth ? "41.9000" : "41.8820")));
            rows.add(new NpsTrustFeedClient.NavRow(POP_G, suffix + "G - TIER I", pfm, day, new BigDecimal(fifteenth ? "37.6000" : "37.5541")));
            catalogService.apply(rows);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] pdf(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/nps/nps-protean-" + name + ".pdf"));
    }

    private UUID account(ApiTestClient client, String pran, String password) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("pran", pran, "holderName", "Ravi", "relation", "SELF",
                "sector", "ALL_CITIZEN", "cra", "PROTEAN"));
        if (password != null) {
            body.put("statementPassword", password);
        }
        return client.create(BASE + "/accounts", body);
    }

    private UUID freshPran(String password) throws Exception {
        return account(api, String.valueOf(900000000000L + (++seq)), password);
    }

    private GmailMessage statementMail(String id, String from) {
        return new GmailMessage(id, Instant.parse("2025-02-02T05:00:00Z"), "Protean <" + from + ">",
                "Your NPS Transaction Statement", "snippet", "see attached", null,
                List.of(new GmailAttachment("att-" + id, "statement.pdf", "application/pdf", null)));
    }

    private void servePdf(String id, String fixture) throws Exception {
        when(gmailEngine.fetchAttachmentContent(any(), eq(id), eq("att-" + id))).thenReturn(pdf(fixture));
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq(id))).thenReturn(statementMail(id, STATEMENT_SENDER));
    }

    private GmailDocumentHandler.Handled handle(GmailMessage message) {
        return UserContextHelper.callAs(userId, () -> handler.handle(connection, message));
    }

    private GmailMessage alert(String from, String subject, String body, String dateTime) {
        return new GmailMessage("alert-" + (++seq), Instant.parse(dateTime), "NPS <" + from + ">", subject, "", body, null, List.of());
    }

    private static String request(String pranTail, String ack, String tier, String amount) {
        return "The contribution in your NPS account through eNPS portal is currently under process and the related "
                + "details are as follow:\n1. PRAN : XXXXXXXX" + pranTail + "\n2. Acknowledgment Number : " + ack
                + "\n3. Tier Type : " + tier + " account\n4. Amount : Rs. " + amount + "\n";
    }

    private static String credit(String pranTail, String tier, String amount, String word, String nav, String on) {
        return "Units with NAV of " + nav + " have been credited for " + tier + " in your PRAN XX" + pranTail
                + " against Rs. " + amount + " as " + word + " contribution on " + on + ".";
    }

    private List<Map<String, Object>> contributions(UUID account) {
        return jdbc.queryForList("SELECT * FROM nps_entry WHERE account_id = ? AND type = 'CONTRIBUTION' ORDER BY posted_date, created_at",
                account.toString());
    }

    private Map<String, BigDecimal> lines(Object entryId) {
        Map<String, BigDecimal> out = new TreeMap<>();
        jdbc.query("SELECT scheme_code, units FROM nps_entry_line WHERE entry_id = ?",
                rs -> { out.put(rs.getString(1), rs.getBigDecimal(2).setScale(4)); }, entryId.toString());
        return out;
    }

    private int count(String table, UUID account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE account_id = ?", Integer.class, account.toString());
    }

    private int pendingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM nps_gmail_pending WHERE user_id = ?", Integer.class, userId.toString());
    }

    private static LocalDate date(Object sqlDate) {
        return sqlDate instanceof java.sql.Timestamp t ? t.toLocalDateTime().toLocalDate() : ((java.sql.Date) sqlDate).toLocalDate();
    }

    private void setAllocation(UUID account) throws Exception {
        api.putJson(BASE + "/accounts/" + account + "/allocation", Map.of("tier", "I", "choice", "AGGRESSIVE CHOICE",
                "rows", List.of(Map.of("schemeCode", POP_E, "percent", 50), Map.of("schemeCode", POP_C, "percent", 30),
                        Map.of("schemeCode", POP_G, "percent", 20))), 200);
    }

    // ------------------------------------------------------------------ dispatch

    @Test
    void theHandlerTakesOnlyTheSixExactNpsSenders() {
        GmailMessage m = alert("x@example.com", "s", "b", "2025-01-15T05:00:00Z");
        for (String s : List.of("nps-statements@mailer.proteantech.in", "nps-statements@proteantech.in", "cra@nsdl.co.in",
                "nps-alerts@proteantech.in", "cra-alerts@nsdl.co.in", "enps-alerts@proteantech.in")) {
            assertTrue(handler.supports(s, m), s);
        }
        assertFalse(handler.supports("communications.cra@nsdl.co.in", m));
        assertFalse(handler.supports("alerts@hdfcbank.net", m));
        assertEquals(6, handler.senderAddresses().size());
        assertEquals(LocalDate.of(2013, 1, 1), handler.backfillFrom());
    }

    // ------------------------------------------------------------------ statement mails

    @Test
    void aStatementMailIsAppliedAsAGmailStatementAndTheAccountsPasswordIsUsed() throws Exception {
        UUID acc = account(api, PRAN, "ravi1406");
        servePdf("stmt-1", "wide-contribution");

        GmailDocumentHandler.Handled h = handle(statementMail("stmt-1", STATEMENT_SENDER));

        assertEquals(GmailProcessedStatus.NPS_IMPORTED, h.status());
        Map<String, Object> stmt = jdbc.queryForMap("SELECT source, gmail_message_id FROM nps_statement WHERE account_id = ?", acc.toString());
        assertEquals("GMAIL", stmt.get("source"));
        assertEquals("stmt-1", stmt.get("gmail_message_id"));
        List<Map<String, Object>> contributions = contributions(acc);
        assertEquals(1, contributions.size());
        assertEquals("CONFIRMED", contributions.get(0).get("status"));
        assertEquals(0, pendingCount());
        // the same mail again is a no-op on the ledger
        int entries = count("nps_entry", acc);
        handle(statementMail("stmt-1", STATEMENT_SENDER));
        assertEquals(entries, count("nps_entry", acc));
        assertEquals(1, count("nps_statement", acc));
    }

    @Test
    void aStatementMailWithoutAPdfIsIgnored() {
        GmailMessage m = new GmailMessage("nopdf", Instant.now(), STATEMENT_SENDER, "Hello", "", "text", null, List.of());
        assertEquals(GmailProcessedStatus.NPS_IGNORED, handle(m).status());
    }

    @Test
    void nothingIsWrittenWhenReadingNpsEmailsIsOffOnEveryAccount() throws Exception {
        UUID acc = account(api, PRAN, "ravi1406");
        api.putJson(BASE + "/accounts/" + acc, Map.of("readGmail", false), 200);
        servePdf("stmt-off", "wide-contribution");

        assertEquals(GmailProcessedStatus.NPS_IGNORED, handle(statementMail("stmt-off", STATEMENT_SENDER)).status());
        assertEquals(GmailProcessedStatus.NPS_IGNORED, handle(alert(ENPS, "Protean - Confirmation of contribution request under National Pension System - NPS",
                request("5678", "11", "Tier I", "1000.00"), "2025-01-15T05:00:00Z")).status());
        assertEquals(0, count("nps_statement", acc));
        assertEquals(0, count("nps_entry", acc));
    }

    @Test
    void aLockedMailIsHeldAndOnePasswordOpensEveryLockedStatementIntoOneDetection() throws Exception {
        // no profile, no saved password: all three are locked
        List<String> ids = List.of("lock-1", "lock-2", "lock-3");
        servePdf("lock-1", "wide-contribution");
        servePdf("lock-2", "wide-empty");
        servePdf("lock-3", "stacked-rebalance");
        for (String id : ids) {
            assertEquals(GmailProcessedStatus.NPS_PASSWORD_NEEDED, handle(statementMail(id, STATEMENT_SENDER)).status());
        }
        handle(statementMail("lock-1", STATEMENT_SENDER)); // a re-run keeps the one row
        assertEquals(3, pendingCount());
        Map<String, Object> row = jdbc.queryForMap("SELECT reason, status, pran_last4, holder_name FROM nps_gmail_pending WHERE gmail_message_id = 'lock-1'");
        assertEquals("PASSWORD", row.get("reason"));
        assertEquals("PENDING", row.get("status"));
        assertNull(row.get("pran_last4"));
        assertNull(row.get("holder_name"));

        JsonNode summary = api.getJson(BASE + "/summary", 200);
        assertEquals(0, summary.get("pendingGmail").size());
        assertEquals(3, summary.get("lockedGmail").asInt());
        List<InboxItemResponse> locked = collector.collect(userId, AppTime.today());
        assertEquals(1, locked.size());
        assertEquals("nps_password", locked.get(0).kind());
        assertEquals("3 NPS statements need a password", locked.get(0).title());
        assertEquals("/investments/nps", locked.get(0).href());

        JsonNode wrong = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "nope"), 200);
        assertEquals(0, wrong.get("opened").asInt());
        assertEquals(3, wrong.get("stillLocked").asInt());
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("pendingGmail").size());

        JsonNode right = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "ravi1406"), 200);
        assertEquals(3, right.get("opened").asInt());
        assertEquals(0, right.get("stillLocked").asInt());
        JsonNode after = api.getJson(BASE + "/summary", 200);
        assertEquals(0, after.get("lockedGmail").asInt());
        assertEquals(1, after.get("pendingGmail").size());
        JsonNode d = after.get("pendingGmail").get(0);
        assertEquals(3, d.get("statementCount").asInt());
        assertEquals("5678", d.get("pranLast4").asText());
        assertEquals(LocalDate.parse(jdbc.queryForObject("SELECT CAST(MIN(period_start) AS VARCHAR(10)) FROM nps_gmail_pending WHERE user_id = ?", String.class, userId.toString())),
                LocalDate.parse(d.get("firstPeriodStart").asText()));
        assertEquals(LocalDate.parse(jdbc.queryForObject("SELECT CAST(MAX(period_end) AS VARCHAR(10)) FROM nps_gmail_pending WHERE user_id = ?", String.class, userId.toString())),
                LocalDate.parse(d.get("lastPeriodEnd").asText()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM nps_gmail_pending WHERE user_id = ? AND reason = 'NEW_ACCOUNT' AND status = 'PENDING'", Integer.class, userId.toString()));
        assertEquals(0, accountRepository.findAllByUser_Id(userId).size());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE user_id = ?", Integer.class, userId.toString()));
        List<InboxItemResponse> rows = collector.collect(userId, AppTime.today());
        assertEquals(1, rows.size());
        assertEquals("nps_new_account", rows.get(0).kind());
    }

    @Test
    void anUnlockBatchRotatesSoRowsThatFailedGoToTheBackAndTheRestAreReached() throws Exception {
        int total = NpsGmailDetectionService.UNLOCK_BATCH + 5;
        for (int i = 0; i < total; i++) {
            String id = "rot-" + i;
            servePdf(id, "wide-contribution");
            assertEquals(GmailProcessedStatus.NPS_PASSWORD_NEEDED, handle(statementMail(id, STATEMENT_SENDER)).status());
        }
        assertEquals(total, pendingCount());

        JsonNode wrong = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "passwordA"), 200);
        assertEquals(0, wrong.get("opened").asInt());
        assertEquals(total, wrong.get("stillLocked").asInt());
        List<String> untried = jdbc.queryForList("SELECT gmail_message_id FROM nps_gmail_pending WHERE user_id = ? "
                + "AND last_tried_at IS NULL", String.class, userId.toString());
        assertEquals(5, untried.size());

        api.postJson(BASE + "/gmail-detections/password", Map.of("password", "ravi1406"), 200);
        for (String id : untried) {
            Map<String, Object> row = jdbc.queryForMap("SELECT reason, status FROM nps_gmail_pending WHERE user_id = ? "
                    + "AND gmail_message_id = ?", userId.toString(), id);
            assertFalse("PASSWORD".equals(row.get("reason")) && "PENDING".equals(row.get("status")), id + " was never reached");
        }
    }

    @Test
    void aRowQueuedWhileTheImportJobRunsIsImportedByThatSameJobAndEachRowIsTriedOnce() throws Exception {
        knowTheUsersPassword();
        servePdf("loop-1", "wide-contribution");
        servePdf("loop-2", "wide-empty");
        servePdf("loop-3", "wide-empty");
        handle(statementMail("loop-1", STATEMENT_SENDER));
        handle(statementMail("loop-2", STATEMENT_SENDER));
        handle(statementMail("loop-3", STATEMENT_SENDER));
        // loop-2 is not queued yet; loop-3's mail is gone, so it fails
        jdbc.update("UPDATE nps_gmail_pending SET status = 'DONE' WHERE user_id = ? AND gmail_message_id = 'loop-2'", userId.toString());
        jdbc.update("UPDATE nps_gmail_pending SET status = 'DONE' WHERE user_id = ? AND gmail_message_id = 'loop-3'", userId.toString());
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq("loop-3")))
                .thenThrow(new com.financeos.gmail.internal.MessageGoneException("loop-3"));
        // the first pass fetches loop-1; at that moment loop-2 and loop-3 are queued (as another request would)
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq("loop-1"))).thenAnswer(inv -> {
            jdbc.update("UPDATE nps_gmail_pending SET status = 'PENDING' WHERE user_id = ? AND gmail_message_id IN ('loop-2', 'loop-3')",
                    userId.toString());
            return statementMail("loop-1", STATEMENT_SENDER);
        });

        UUID accountId = account(api, PRAN, null);

        awaitImportJobs();
        assertEquals(2, count("nps_statement", accountId));
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM nps_gmail_pending WHERE user_id = ? AND gmail_message_id = 'loop-2'",
                String.class, userId.toString()));
        Map<String, Object> failed = jdbc.queryForMap("SELECT status, attempts FROM nps_gmail_pending WHERE user_id = ? "
                + "AND gmail_message_id = 'loop-3'", userId.toString());
        assertEquals("PENDING", failed.get("status"));
        assertEquals(1, ((Number) failed.get("attempts")).intValue());
    }

    @Test
    void aTypedPasswordThatDidNotOpenThePdfIsNotSavedOnTheAccount() throws Exception {
        UUID acc = account(api, PRAN, null);
        servePdf("stmt-pw2", "wide-contribution");
        assertEquals(GmailProcessedStatus.NPS_PASSWORD_NEEDED, handle(statementMail("stmt-pw2", STATEMENT_SENDER)).status());
        // the right password reaches the account another way before the user types a wrong one
        NpsAccount a = accountRepository.findByIdAndUser_Id(acc, userId).orElseThrow();
        a.setStatementPassword("ravi1406");
        accountRepository.saveAndFlush(a);

        JsonNode done = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "not-the-one"), 200);

        assertEquals(1, done.get("opened").asInt());
        assertEquals(0, done.get("stillLocked").asInt());
        assertEquals(1, count("nps_statement", acc));
        assertEquals("ravi1406", accountRepository.findByIdAndUser_Id(acc, userId).map(NpsAccount::getStatementPassword).orElseThrow());
    }

    @Test
    void aTypedPasswordThatOpensAnExistingAccountsStatementImportsItAndIsSavedOnThatAccount() throws Exception {
        UUID acc = account(api, PRAN, null);
        servePdf("stmt-pw3", "wide-contribution");
        handle(statementMail("stmt-pw3", STATEMENT_SENDER));

        JsonNode done = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "ravi1406"), 200);

        assertEquals(1, done.get("opened").asInt());
        assertEquals(1, count("nps_statement", acc));
        assertEquals("ravi1406", accountRepository.findByIdAndUser_Id(acc, userId).map(NpsAccount::getStatementPassword).orElseThrow());
        assertEquals("DONE", jdbc.queryForObject("SELECT status FROM nps_gmail_pending WHERE gmail_message_id = 'stmt-pw3'", String.class));
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("lockedGmail").asInt());
    }

    @Test
    void aCreditAlertKeepsTheTransactionLinkOfItsEntry() throws Exception {
        UUID acc = account(api, PRAN, "ravi1406");
        setAllocation(acc);
        String subject = "Protean - Confirmation of contribution request under National Pension System - NPS";
        handle(alert(ENPS, subject, request("5678", "12345678", "Tier I", "37350.00"), "2025-01-15T05:00:00Z"));
        UUID entryId = UUID.fromString(contributions(acc).get(0).get("id").toString());
        UUID bankAccount = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank 1234", "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID txn = api.create("/api/v1/transactions", Map.of("accountId", bankAccount.toString(), "date", "2025-01-15",
                "amount", -37350, "description", "NPS TRUST"));
        jdbc.update("UPDATE nps_entry SET transaction_id = ? WHERE id = ?", txn.toString(), entryId.toString());

        handle(alert(CREDIT_SENDER, "Protean -  Intimation of Contribution Credit",
                credit("5678", "TIER1", "37350.00", "Voluntary", "15/01/2025", "17/01/2025"), "2025-01-17T05:00:00Z"));

        Map<String, Object> after = contributions(acc).get(0);
        assertEquals(entryId.toString(), after.get("id").toString());
        assertEquals("ESTIMATED", after.get("status"));
        assertEquals(txn.toString(), after.get("transaction_id").toString());
    }

    // ------------------------------------------------------------------ detect and hold

    private void knowTheUsersPassword() {
        User user = userRepository.findById(userId).orElseThrow();
        user.setDisplayName("Ravi Kumar");
        user.setBirthDate("1990-06-14");
        userRepository.saveAndFlush(user);
    }

    /** Waits for the background import jobs of the user to finish (the worker runs them off the request). */
    private void awaitImportJobs() throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE user_id = ? AND type = 'NPS_GMAIL_IMPORT' "
                    + "AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')", Integer.class, userId.toString());
            Integer all = jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE user_id = ? AND type = 'NPS_GMAIL_IMPORT'",
                    Integer.class, userId.toString());
            if (all > 0 && open == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the NPS import job did not finish");
    }

    private String detectionKey() throws Exception {
        return api.getJson(BASE + "/summary", 200).get("pendingGmail").get(0).get("key").asText();
    }

    private int statementCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE user_id = ?", Integer.class, userId.toString());
    }

    @Test
    void aStatementOfAPranWithNoAccountIsHeldNeverImportedAndNoAccountIsCreated() throws Exception {
        knowTheUsersPassword(); // the holder even has the user's first name: still never guessed
        servePdf("stmt-new", "wide-contribution");

        assertEquals(GmailProcessedStatus.NPS_ACCOUNT_NEEDED, handle(statementMail("stmt-new", STATEMENT_SENDER)).status());

        assertEquals(0, accountRepository.findAllByUser_Id(userId).size());
        assertEquals(0, statementCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_entry WHERE user_id = ?", Integer.class, userId.toString()));
        Map<String, Object> held = jdbc.queryForMap("SELECT reason, status, pran_encrypted, pran_last4, holder_name, period_start, period_end, "
                + "pop_name, password_encrypted, connection_id FROM nps_gmail_pending WHERE user_id = ?", userId.toString());
        assertEquals("NEW_ACCOUNT", held.get("reason"));
        assertEquals("PENDING", held.get("status"));
        assertEquals("5678", held.get("pran_last4"));
        assertEquals("RAVI KUMAR SHARMA", held.get("holder_name"));
        assertEquals(LocalDate.of(2025, 1, 1), date(held.get("period_start")));
        assertEquals(LocalDate.of(2025, 1, 31), date(held.get("period_end")));
        assertTrue(held.get("pop_name").toString().toLowerCase().contains("enps"));
        // the PRAN and the password are stored encrypted, the PDF not at all
        assertFalse(held.get("pran_encrypted").toString().contains(PRAN));
        assertFalse(held.get("password_encrypted").toString().contains("ravi1406"));
        assertEquals(connection.getId().toString(), held.get("connection_id"));
        NpsGmailPending entity = jdbc.queryForList("SELECT id FROM nps_gmail_pending WHERE user_id = ?", String.class, userId.toString())
                .stream().map(id -> pendingRepository.findById(UUID.fromString(id)).orElseThrow()).findFirst().orElseThrow();
        assertEquals(PRAN, entity.getPran());
        assertEquals("ravi1406", entity.getPassword());

        JsonNode summary = api.getJson(BASE + "/summary", 200);
        assertEquals(0, summary.get("accounts").size());
        assertEquals(1, summary.get("pendingGmail").size());
        JsonNode d = summary.get("pendingGmail").get(0);
        assertEquals(1, d.get("statementCount").asInt());
        assertEquals("ALL_CITIZEN", d.get("sectorGuess").asText());
        assertEquals("RAVI KUMAR SHARMA", d.get("holderName").asText());
        List<InboxItemResponse> rows = collector.collect(userId, AppTime.today());
        assertEquals(1, rows.size());
        InboxItemResponse row = rows.get(0);
        assertEquals("nps_new_account", row.kind());
        assertEquals("NPS account found in Gmail (••5678, RAVI KUMAR SHARMA)", row.title());
        assertEquals("1 statement · Jan 2025 – Jan 2025", row.subtitle());
        assertEquals(d.get("key").asText(), row.refs().npsPendingId().toString());
        assertEquals(List.of("nps_add_self", "nps_add_child", "nps_ignore"), row.actions().stream().map(x -> x.type()).toList());
        // the same mail handled again keeps one row
        handle(statementMail("stmt-new", STATEMENT_SENDER));
        assertEquals(1, pendingCount());
    }

    @Test
    void aSecondStatementOfTheSamePranIsTheSameDetectionWithCountTwo() throws Exception {
        knowTheUsersPassword();
        servePdf("det-1", "wide-contribution");
        servePdf("det-2", "wide-empty");
        handle(statementMail("det-1", STATEMENT_SENDER));
        String key = detectionKey();

        assertEquals(GmailProcessedStatus.NPS_ACCOUNT_NEEDED, handle(statementMail("det-2", STATEMENT_SENDER)).status());

        JsonNode summary = api.getJson(BASE + "/summary", 200);
        assertEquals(1, summary.get("pendingGmail").size());
        assertEquals(key, summary.get("pendingGmail").get(0).get("key").asText());
        assertEquals(2, summary.get("pendingGmail").get(0).get("statementCount").asInt());
        assertEquals(1, collector.collect(userId, AppTime.today()).size());
        assertEquals(2, pendingCount());
    }

    @Test
    void theGoAheadCreatesTheChildAccountAndImportsEveryHeldStatementInTheBackground() throws Exception {
        knowTheUsersPassword();
        servePdf("go-1", "wide-contribution");
        servePdf("go-2", "wide-empty");
        handle(statementMail("go-1", STATEMENT_SENDER));
        handle(statementMail("go-2", STATEMENT_SENDER));
        String key = detectionKey();

        JsonNode accepted = api.postJson(BASE + "/gmail-detections/" + key + "/account", Map.of("relation", "CHILD"), 200);

        UUID accountId = UUID.fromString(accepted.get("accountId").asText());
        assertEquals(2, accepted.get("importing").asInt());
        awaitImportJobs();
        Map<String, Object> created = jdbc.queryForMap("SELECT relation, sector, cra, pran_last4, holder_name FROM nps_account WHERE id = ?", accountId.toString());
        assertEquals("CHILD", created.get("relation"));
        assertEquals("ALL_CITIZEN", created.get("sector")); // the statement came through eNPS
        assertEquals("5678", created.get("pran_last4"));
        assertEquals("RAVI KUMAR SHARMA", created.get("holder_name"));
        assertEquals(2, count("nps_statement", accountId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM nps_statement WHERE account_id = ? AND gmail_message_id = 'go-1' AND source = 'GMAIL'", Integer.class, accountId.toString()));
        assertEquals(1, contributions(accountId).size());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM nps_gmail_pending WHERE user_id = ? AND status = 'DONE'", Integer.class, userId.toString()));
        assertEquals("ravi1406", accountRepository.findByIdAndUser_Id(accountId, userId).map(NpsAccount::getStatementPassword).orElseThrow());
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("pendingGmail").size());
        assertTrue(collector.collect(userId, AppTime.today()).isEmpty());
        // the same key is gone now
        assertEquals(404, api.post(BASE + "/gmail-detections/" + key + "/account", Map.of("relation", "SELF")).getStatus());
    }

    @Test
    void theGoAheadWithoutAnEnpsPopLeavesTheSectorNotSetAndARequestedSectorWins() throws Exception {
        knowTheUsersPassword();
        servePdf("sec-1", "stacked-rebalance"); // no contributions: no eNPS POP
        handle(statementMail("sec-1", STATEMENT_SENDER));
        JsonNode d = api.getJson(BASE + "/summary", 200).get("pendingGmail").get(0);
        assertTrue(d.get("sectorGuess") == null || d.get("sectorGuess").isNull());

        JsonNode accepted = api.postJson(BASE + "/gmail-detections/" + d.get("key").asText() + "/account", Map.of("relation", "SELF"), 200);

        awaitImportJobs();
        UUID accountId = UUID.fromString(accepted.get("accountId").asText());
        assertNull(jdbc.queryForObject("SELECT sector FROM nps_account WHERE id = ?", String.class, accountId.toString()));
        JsonNode account = api.getJson(BASE + "/summary", 200).get("accounts").get(0);
        assertTrue(account.get("sector") == null || account.get("sector").isNull());
        assertEquals(1, count("nps_statement", accountId));
        // it can be set later
        api.putJson(BASE + "/accounts/" + accountId, Map.of("sector", "CORPORATE"), 200);
        assertEquals("CORPORATE", jdbc.queryForObject("SELECT sector FROM nps_account WHERE id = ?", String.class, accountId.toString()));
    }

    @Test
    void aRequestedSectorWinsOverTheGuess() throws Exception {
        knowTheUsersPassword();
        servePdf("sec-3", "wide-contribution");
        handle(statementMail("sec-3", STATEMENT_SENDER));

        JsonNode accepted = api.postJson(BASE + "/gmail-detections/" + detectionKey() + "/account",
                Map.of("relation", "SELF", "sector", "GOVT"), 200);

        awaitImportJobs();
        assertEquals("GOVT", jdbc.queryForObject("SELECT sector FROM nps_account WHERE id = ?", String.class, accepted.get("accountId").asText()));
    }

    @Test
    void theUserAddingTheAccountThemselvesImportsTheHeldStatements() throws Exception {
        knowTheUsersPassword();
        servePdf("own-1", "wide-contribution");
        servePdf("own-2", "wide-empty");
        handle(statementMail("own-1", STATEMENT_SENDER));
        handle(statementMail("own-2", STATEMENT_SENDER));

        UUID acc = account(api, PRAN, null);

        awaitImportJobs();
        assertEquals(2, count("nps_statement", acc));
        assertEquals(1, contributions(acc).size());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM nps_gmail_pending WHERE user_id = ? AND status = 'DONE'", Integer.class, userId.toString()));
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("pendingGmail").size());
    }

    @Test
    void anAccountAddedWithAnOtherPranQueuesNothing() throws Exception {
        knowTheUsersPassword();
        servePdf("none-1", "wide-contribution");
        handle(statementMail("none-1", STATEMENT_SENDER));

        account(api, "900000000001", null);

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE user_id = ? AND type = 'NPS_GMAIL_IMPORT'", Integer.class, userId.toString()));
        assertEquals(1, api.getJson(BASE + "/summary", 200).get("pendingGmail").size());
    }

    @Test
    void ignoringAPranIgnoresItsLaterMailsAndCreatingTheAccountAfterwardsImportsTheHeldOnes() throws Exception {
        knowTheUsersPassword();
        servePdf("ign-1", "wide-contribution");
        handle(statementMail("ign-1", STATEMENT_SENDER));
        String key = detectionKey();

        api.deleteJson(BASE + "/gmail-detections/" + key, 204);

        assertEquals("IGNORED", jdbc.queryForObject("SELECT status FROM nps_gmail_pending WHERE user_id = ?", String.class, userId.toString()));
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("pendingGmail").size());
        assertTrue(collector.collect(userId, AppTime.today()).isEmpty());
        assertEquals(0, accountRepository.findAllByUser_Id(userId).size());
        assertEquals(0, statementCount());
        // a later mail of that PRAN is ignored and leaves no row
        servePdf("ign-2", "wide-empty");
        GmailDocumentHandler.Handled later = handle(statementMail("ign-2", STATEMENT_SENDER));
        assertEquals(GmailProcessedStatus.NPS_IGNORED, later.status());
        // the later mail is kept as an IGNORED row with its data (so it imports with the account), not shown anywhere
        assertEquals(2, pendingCount());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM nps_gmail_pending WHERE user_id = ? AND status = 'IGNORED' "
                + "AND reason = 'NEW_ACCOUNT' AND pran_last4 = '5678' AND period_end IS NOT NULL", Integer.class, userId.toString()));
        assertTrue(collector.collect(userId, AppTime.today()).isEmpty());

        // creating the account is the user changing their mind: both ignored statements import
        UUID acc = account(api, PRAN, null);
        awaitImportJobs();
        assertEquals(2, count("nps_statement", acc));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM nps_gmail_pending WHERE user_id = ? AND status = 'DONE'", Integer.class, userId.toString()));
    }

    @Test
    void aHeldStatementWhoseMailIsGoneStaysPendingWithTheReason() throws Exception {
        knowTheUsersPassword();
        servePdf("gone-1", "wide-contribution");
        handle(statementMail("gone-1", STATEMENT_SENDER));
        String key = detectionKey();
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq("gone-1")))
                .thenThrow(new com.financeos.gmail.internal.MessageGoneException("gone-1"));

        JsonNode accepted = api.postJson(BASE + "/gmail-detections/" + key + "/account", Map.of("relation", "SELF"), 200);

        awaitImportJobs();
        UUID accountId = UUID.fromString(accepted.get("accountId").asText());
        Map<String, Object> row = jdbc.queryForMap("SELECT status, last_error FROM nps_gmail_pending WHERE user_id = ?", userId.toString());
        assertEquals("PENDING", row.get("status"));
        assertEquals("The email is no longer in your mailbox.", row.get("last_error"));
        assertEquals(0, count("nps_statement", accountId));
        // the account exists, so it is no detection any more
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("pendingGmail").size());
    }

    @Test
    void theDetectionEndpointsAreTenantScoped() throws Exception {
        knowTheUsersPassword();
        servePdf("ten-1", "wide-contribution");
        handle(statementMail("ten-1", STATEMENT_SENDER));
        String key = detectionKey();

        assertEquals(404, otherApi.post(BASE + "/gmail-detections/" + key + "/account", Map.of("relation", "SELF")).getStatus());
        otherApi.deleteJson(BASE + "/gmail-detections/" + key, 404);
        assertEquals(0, otherApi.getJson(BASE + "/summary", 200).get("pendingGmail").size());
        JsonNode otherPassword = otherApi.postJson(BASE + "/gmail-detections/password", Map.of("password", "ravi1406"), 200);
        assertEquals(0, otherPassword.get("opened").asInt());
        assertEquals(0, otherPassword.get("stillLocked").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_account WHERE user_id = ?", Integer.class, otherUserId.toString()));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM nps_gmail_pending WHERE user_id = ?", String.class, userId.toString()));
        assertEquals(1, api.getJson(BASE + "/summary", 200).get("pendingGmail").size());
    }

    @Test
    void theGoAheadNeedsARelation() throws Exception {
        knowTheUsersPassword();
        servePdf("rel-1", "wide-contribution");
        handle(statementMail("rel-1", STATEMENT_SENDER));

        assertEquals(400, api.post(BASE + "/gmail-detections/" + detectionKey() + "/account", Map.of()).getStatus());
        assertEquals(0, accountRepository.findAllByUser_Id(userId).size());
    }

    // ------------------------------------------------------------------ alerts

    @Test
    void aRequestConfirmationCreatesOnePendingEmailEntryOnTheMatchingAccount() throws Exception {
        UUID acc = account(api, "900000000001", null);
        GmailMessage m = alert(ENPS, "Protean - Confirmation of contribution request under National Pension System - NPS",
                request("0001", "12345678", "Tier I", "37350.00"), "2025-01-15T05:00:00Z");

        assertEquals(GmailProcessedStatus.NPS_ENTRY, handle(m).status());
        handle(m);

        List<Map<String, Object>> entries = contributions(acc);
        assertEquals(1, entries.size());
        Map<String, Object> e = entries.get(0);
        assertEquals("PENDING", e.get("status"));
        assertEquals("EMAIL", e.get("source"));
        assertEquals("SELF", e.get("contributor"));
        assertEquals("I", e.get("tier"));
        assertEquals("email|ack|12345678", e.get("dedupe_key"));
        assertEquals(LocalDate.of(2025, 1, 15), date(e.get("posted_date")));
        assertEquals(0, new BigDecimal("37350.00").compareTo((BigDecimal) e.get("amount")));
        assertTrue(lines(e.get("id")).isEmpty());
    }

    @Test
    void anAlertWithNoSingleMatchingAccountIsIgnored() throws Exception {
        account(api, "900000000001", null);
        String subject = "Protean - Confirmation of contribution request under National Pension System - NPS";
        GmailMessage none = alert(ENPS, subject, request("9999", "1", "Tier I", "100.00"), "2025-01-15T05:00:00Z");
        assertEquals(GmailProcessedStatus.NPS_IGNORED, handle(none).status());
        // two accounts ending alike: not guessed either
        account(api, "800000000001", null);
        GmailMessage ambiguous = alert(ENPS, subject, request("0001", "2", "Tier I", "100.00"), "2025-01-15T05:00:00Z");
        assertEquals(GmailProcessedStatus.NPS_IGNORED, handle(ambiguous).status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM nps_entry WHERE user_id = ?", Integer.class, userId.toString()));
        // an unrelated mail from the same sender is ignored too
        assertEquals(GmailProcessedStatus.NPS_IGNORED, handle(alert(ENPS, "Your password was changed", "no details", "2025-01-15T05:00:00Z")).status());
    }

    @Test
    void requestThenCreditThenStatementIsOneEntryThatEndsConfirmedWithTheStatementsUnits() throws Exception {
        UUID acc = account(api, PRAN, "ravi1406");
        setAllocation(acc);
        String subject = "Protean - Confirmation of contribution request under National Pension System - NPS";

        // a) request: pending, counts for nothing
        handle(alert(ENPS, subject, request("5678", "12345678", "Tier I", "37350.00"), "2025-01-15T05:00:00Z"));
        Map<String, Object> pending = contributions(acc).get(0);
        assertEquals("PENDING", pending.get("status"));

        // b) credit: the same entry, now estimated at the credited NAV with the account's allocation
        GmailMessage credit = alert(CREDIT_SENDER, "Protean -  Intimation of Contribution Credit",
                credit("5678", "TIER1", "37350.00", "Voluntary", "15/01/2025", "17/01/2025"), "2025-01-17T05:00:00Z");
        assertEquals(GmailProcessedStatus.NPS_ENTRY, handle(credit).status());
        List<Map<String, Object>> afterCredit = contributions(acc);
        assertEquals(1, afterCredit.size());
        Map<String, Object> estimated = afterCredit.get(0);
        assertEquals(pending.get("id"), estimated.get("id"));
        assertEquals("ESTIMATED", estimated.get("status"));
        assertEquals(LocalDate.of(2025, 1, 17), date(estimated.get("posted_date")));
        assertEquals(LocalDate.of(2025, 1, 15), date(estimated.get("nav_date")));
        assertEquals("EMAIL", estimated.get("source"));
        assertEquals(Map.of(POP_E, new BigDecimal("298.8000"), POP_C, new BigDecimal("267.4224"), POP_G, new BigDecimal("198.6702")),
                lines(estimated.get("id")));

        // the statement confirms it in place
        servePdf("stmt-chain", "wide-contribution");
        assertEquals(GmailProcessedStatus.NPS_IMPORTED, handle(statementMail("stmt-chain", STATEMENT_SENDER)).status());
        List<Map<String, Object>> done = contributions(acc);
        assertEquals(1, done.size());
        assertEquals(pending.get("id"), done.get(0).get("id"));
        assertEquals("CONFIRMED", done.get(0).get("status"));
        assertEquals("STATEMENT", done.get(0).get("source"));
        assertEquals(LocalDate.of(2025, 1, 15), date(done.get(0).get("posted_date")));
        assertEquals(Map.of(POP_E, new BigDecimal("299.0790"), POP_C, new BigDecimal("267.5374"), POP_G, new BigDecimal("198.9130")),
                lines(done.get(0).get("id")));
    }

    @Test
    void theOrderTheMailsArriveInDoesNotDuplicateTheContribution() throws Exception {
        UUID acc = account(api, PRAN, "ravi1406");
        setAllocation(acc);
        String subject = "Protean - Confirmation of contribution request under National Pension System - NPS";

        // newest first, as a backfill may: credit, then its request
        GmailMessage credit = alert(CREDIT_SENDER, "Protean -  Intimation of Contribution Credit",
                credit("5678", "TIER1", "37350.00", "Voluntary", "15/01/2025", "17/01/2025"), "2025-01-17T05:00:00Z");
        handle(credit);
        handle(alert(ENPS, subject, request("5678", "12345678", "Tier I", "37350.00"), "2025-01-15T05:00:00Z"));
        assertEquals(1, contributions(acc).size());
        assertEquals("ESTIMATED", contributions(acc).get(0).get("status"));
        // the same credit from the other credit sender is the same contribution
        handle(alert("cra-alerts@nsdl.co.in", "Protean -  Intimation of Contribution Credit",
                credit("5678", "TIER1", "37350.00", "Voluntary", "15/01/2025", "17/01/2025"), "2025-01-17T05:05:00Z"));
        assertEquals(1, contributions(acc).size());

    }

    @Test
    void aCreditMailAfterTheStatementThatConfirmedItWritesNothing() throws Exception {
        UUID acc = account(api, PRAN, "ravi1406");
        setAllocation(acc);
        servePdf("stmt-first", "wide-contribution");
        assertEquals(GmailProcessedStatus.NPS_IMPORTED, handle(statementMail("stmt-first", STATEMENT_SENDER)).status());

        GmailMessage credit = alert(CREDIT_SENDER, "Protean -  Intimation of Contribution Credit",
                credit("5678", "TIER1", "37350.00", "Voluntary", "15/01/2025", "17/01/2025"), "2025-01-17T05:00:00Z");
        assertEquals(GmailProcessedStatus.NPS_ENTRY, handle(credit).status());
        handle(alert(ENPS, "Protean - Confirmation of contribution request under National Pension System - NPS",
                request("5678", "12345678", "Tier I", "37350.00"), "2025-01-15T05:00:00Z"));

        assertEquals(1, contributions(acc).size());
        assertEquals("CONFIRMED", contributions(acc).get(0).get("status"));
    }

    @Test
    void aCreditWithNoMatchingPendingRequestCreatesAnEstimatedEntryAndAnEmployerCreditIsEmployer() throws Exception {
        UUID acc = account(api, PRAN, null);
        setAllocation(acc);

        handle(alert(CREDIT_SENDER, "Protean - Intimation of Contribution Credit",
                credit("5678", "TIER1", "5000.00", "Employer", "15/01/2025", "17/01/2025"), "2025-01-17T05:00:00Z"));

        Map<String, Object> e = contributions(acc).get(0);
        assertEquals("ESTIMATED", e.get("status"));
        assertEquals("EMPLOYER", e.get("contributor"));
        assertEquals("email|credit|5678|I|2025-01-15|5000.00", e.get("dedupe_key"));
        assertEquals(3, lines(e.get("id")).size());
    }

    @Test
    void aCreditWithoutAnAllocationOrANavKeepsTheEntryEstimatedWithNoLines() throws Exception {
        UUID noAllocation = account(api, PRAN, null);
        handle(alert(CREDIT_SENDER, "Protean - Intimation of Contribution Credit",
                credit("5678", "TIER1", "5000.00", "Voluntary", "15/01/2025", "17/01/2025"), "2025-01-17T05:00:00Z"));
        Map<String, Object> bare = contributions(noAllocation).get(0);
        assertEquals("ESTIMATED", bare.get("status"));
        assertTrue(lines(bare.get("id")).isEmpty());

        UUID noNav = account(api, "800000009999", null);
        setAllocation(noNav);
        handle(alert(CREDIT_SENDER, "Protean - Intimation of Contribution Credit",
                credit("9999", "TIER1", "6000.00", "Voluntary", "15/01/2020", "17/01/2020"), "2020-01-17T05:00:00Z"));
        Map<String, Object> noPrice = contributions(noNav).get(0);
        assertEquals("ESTIMATED", noPrice.get("status"));
        assertTrue(lines(noPrice.get("id")).isEmpty());
    }

    // ------------------------------------------------------------------ review fixes (S8)

    @Autowired NpsGmailHeldQueue heldQueue;
    @Autowired com.financeos.domain.job.JobService jobService;
    @Autowired com.financeos.domain.job.JobRepository jobRepository;

    private String heldRow(String column, String id) {
        return jdbc.queryForObject("SELECT " + column + " FROM nps_gmail_pending WHERE gmail_message_id = ?", String.class, id);
    }

    private int attempts(String id) {
        return Integer.parseInt(heldRow("attempts", id));
    }

    private int jobCount(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE user_id = ? AND type = 'NPS_GMAIL_IMPORT' AND status = ?",
                Integer.class, userId.toString(), status);
    }

    @Test
    void aFailedHeldImportFailsItsJobIsQueuedAgainBySyncUpToThreeAttemptsThenWaitsForTheUsersRetry() throws Exception {
        knowTheUsersPassword();
        servePdf("fail-1", "wide-contribution");
        handle(statementMail("fail-1", STATEMENT_SENDER));
        String key = detectionKey();
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq("fail-1")))
                .thenThrow(new IllegalStateException("gmail down"));

        api.postJson(BASE + "/gmail-detections/" + key + "/account", Map.of("relation", "SELF"), 200);
        awaitImportJobs();

        assertEquals(1, attempts("fail-1"));
        assertEquals(1, jobCount("FAILED"));
        assertEquals("PENDING", heldRow("status", "fail-1"));
        assertEquals("The import failed (IllegalStateException).", heldRow("last_error", "fail-1"));
        // each Gmail sync queues it again until the third attempt
        for (int expected = 2; expected <= 3; expected++) {
            assertEquals(1, UserContextHelper.callAs(userId, () -> heldQueue.requeueStranded(userId)));
            awaitJobs(expected);
            assertEquals(expected, attempts("fail-1"));
        }
        assertEquals(3, jobCount("FAILED"));
        assertEquals(0, UserContextHelper.callAs(userId, () -> heldQueue.requeueStranded(userId)));
        assertEquals(3, jobCount("FAILED"));
        assertEquals(1, api.getJson(BASE + "/summary", 200).get("failedGmail").asInt());

        // the user's retry resets the attempts and imports once the mail is readable again
        servePdf("fail-1", "wide-contribution");
        JsonNode retry = api.postJson(BASE + "/gmail-detections/retry", Map.of(), 200);
        assertEquals(1, retry.get("importing").asInt());
        awaitJobs(4);
        assertEquals("DONE", heldRow("status", "fail-1"));
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("failedGmail").asInt());
        assertEquals(1, statementCount());
        assertEquals(0, api.postJson(BASE + "/gmail-detections/retry", Map.of(), 200).get("importing").asInt());
    }

    @Test
    void syncRequeuesOnlyRowsOfAccountsReadingGmail() throws Exception {
        knowTheUsersPassword();
        servePdf("off-1", "wide-contribution");
        handle(statementMail("off-1", STATEMENT_SENDER));
        UUID acc = account(api, PRAN, null);
        awaitImportJobs();
        // a stranded row of an account that stopped reading Gmail is left alone
        jdbc.update("UPDATE nps_gmail_pending SET status = 'PENDING', attempts = 0 WHERE gmail_message_id = 'off-1'");
        api.putJson(BASE + "/accounts/" + acc, Map.of("readGmail", false), 200);
        assertEquals(0, UserContextHelper.callAs(userId, () -> heldQueue.requeueStranded(userId)));
        api.putJson(BASE + "/accounts/" + acc, Map.of("readGmail", true), 200);
        assertEquals(1, UserContextHelper.callAs(userId, () -> heldQueue.requeueStranded(userId)));
    }

    /** Waits until {@code n} NPS import jobs of the user have finished. */
    private void awaitJobs(int n) throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until) {
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE user_id = ? AND type = 'NPS_GMAIL_IMPORT' "
                    + "AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')", Integer.class, userId.toString());
            Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM jobs WHERE user_id = ? AND type = 'NPS_GMAIL_IMPORT' "
                    + "AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')", Integer.class, userId.toString());
            if (done >= n && open == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the NPS import jobs did not finish");
    }

    @Test
    void aRequestIsDedupedAgainstAQueuedOrRunningImportJob() {
        com.financeos.domain.job.Job running = new com.financeos.domain.job.Job();
        running.setUserId(userId);
        running.setType(com.financeos.domain.job.JobType.NPS_GMAIL_IMPORT);
        running.setStatus(com.financeos.domain.job.JobStatus.RUNNING);
        running.setTriggerSource(com.financeos.domain.job.JobTrigger.USER);
        running.setDedupKey("nps-gmail-import:dedup-test");
        running.setAttempt(0);
        running.setCancelRequested(false);
        running = jobRepository.saveAndFlush(running);
        // the default dedup swallows a re-queue while the job runs (the running job re-reads the queue between passes)
        assertEquals(running.getId(), jobService.enqueue(userId, com.financeos.domain.job.JobType.NPS_GMAIL_IMPORT,
                com.financeos.domain.job.JobTrigger.USER, null, null, "nps-gmail-import:dedup-test").getId());
    }

    @Test
    void changingAnAccountsPranToOneWithHeldStatementsImportsThem() throws Exception {
        knowTheUsersPassword();
        servePdf("edit-1", "wide-contribution");
        handle(statementMail("edit-1", STATEMENT_SENDER));
        UUID acc = freshPran(null);

        api.putJson(BASE + "/accounts/" + acc, Map.of("pran", PRAN), 200);
        awaitImportJobs();

        assertEquals(1, count("nps_statement", acc));
        assertEquals("DONE", heldRow("status", "edit-1"));
    }

    @Test
    void changingAnAccountsPranToAnIgnoredOneUnignoresAndImportsItsStatements() throws Exception {
        knowTheUsersPassword();
        servePdf("edit-2", "wide-contribution");
        handle(statementMail("edit-2", STATEMENT_SENDER));
        api.deleteJson(BASE + "/gmail-detections/" + detectionKey(), 204);
        UUID acc = freshPran(null);

        api.putJson(BASE + "/accounts/" + acc, Map.of("pran", PRAN), 200);
        awaitImportJobs();

        assertEquals(1, count("nps_statement", acc));
        assertEquals("DONE", heldRow("status", "edit-2"));
    }

    @Test
    void aLockedMailOpenedForAnIgnoredPranBecomesAnIgnoredRowWithItsPran() throws Exception {
        knowTheUsersPassword();
        servePdf("ig-1", "wide-contribution");
        handle(statementMail("ig-1", STATEMENT_SENDER));
        api.deleteJson(BASE + "/gmail-detections/" + detectionKey(), 204);
        // the profile password is gone: the next mail of that PRAN is locked
        User user = userRepository.findById(userId).orElseThrow();
        user.setBirthDate(null);
        userRepository.saveAndFlush(user);
        servePdf("ig-2", "wide-empty");
        assertEquals(GmailProcessedStatus.NPS_PASSWORD_NEEDED, handle(statementMail("ig-2", STATEMENT_SENDER)).status());
        assertEquals("PASSWORD", heldRow("reason", "ig-2"));

        JsonNode answer = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "ravi1406"), 200);

        assertEquals(0, answer.get("opened").asInt());
        assertEquals(0, answer.get("stillLocked").asInt());
        assertEquals("NEW_ACCOUNT", heldRow("reason", "ig-2"));
        assertEquals("IGNORED", heldRow("status", "ig-2"));
        assertEquals("5678", heldRow("pran_last4", "ig-2"));
        assertEquals(0, api.getJson(BASE + "/summary", 200).get("lockedGmail").asInt());
        UUID acc = account(api, PRAN, null);
        awaitImportJobs();
        assertEquals(2, count("nps_statement", acc));
    }

    @Test
    void anUnlockCountsAnUnreadableStatementAsNeitherOpenedNorLockedAndAThrowingOneAsStillLockedWithTheReason() throws Exception {
        servePdf("u-1", "wide-contribution");
        servePdf("u-2", "wide-empty");
        servePdf("u-3", "stacked-rebalance");
        for (String id : List.of("u-1", "u-2", "u-3")) {
            handle(statementMail(id, STATEMENT_SENDER));
        }
        when(gmailEngine.fetchAttachmentContent(any(), eq("u-1"), anyString())).thenReturn("not a pdf".getBytes());
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), eq("u-2"))).thenThrow(new IllegalStateException("boom"));

        JsonNode answer = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "ravi1406"), 200);

        assertEquals(1, answer.get("opened").asInt());
        assertEquals(1, answer.get("stillLocked").asInt());
        assertEquals("IGNORED", heldRow("status", "u-1"));
        assertEquals("PENDING", heldRow("status", "u-2"));
        assertEquals("PASSWORD", heldRow("reason", "u-2"));
        assertEquals("The statement could not be read (IllegalStateException).", heldRow("last_error", "u-2"));
        assertEquals("NEW_ACCOUNT", heldRow("reason", "u-3"));
    }

    @Test
    void anUnlockLeavesTheRowPendingWhenReadingIsOffForItsAccount() throws Exception {
        servePdf("rd-1", "wide-contribution");
        handle(statementMail("rd-1", STATEMENT_SENDER));
        UUID acc = account(api, PRAN, null);
        api.putJson(BASE + "/accounts/" + acc, Map.of("readGmail", false), 200);

        JsonNode answer = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "ravi1406"), 200);

        assertEquals(0, answer.get("opened").asInt());
        assertEquals(1, answer.get("stillLocked").asInt());
        assertEquals("PENDING", heldRow("status", "rd-1"));
        assertEquals("PASSWORD", heldRow("reason", "rd-1"));
        assertEquals("Reading NPS emails is off for this account", heldRow("last_error", "rd-1"));
        assertEquals(0, count("nps_statement", acc));
    }

    @Test
    void oneUnlockTriesAtMostTwentyStatementsAndTheRestStayLocked() throws Exception {
        when(gmailEngine.fetchMessageDetails(any(GmailConnection.class), anyString())).thenAnswer(inv ->
                statementMail(inv.getArgument(1), STATEMENT_SENDER));
        when(gmailEngine.fetchAttachmentContent(any(), anyString(), anyString())).thenReturn("not a pdf".getBytes());
        for (int i = 0; i < 25; i++) {
            NpsGmailPending row = new NpsGmailPending();
            row.setUser(userRepository.getReferenceById(userId));
            row.setConnectionId(connection.getId());
            row.setGmailMessageId("cap-" + i);
            row.setReason(NpsGmailPendingReason.PASSWORD);
            pendingRepository.save(row);
        }

        JsonNode answer = api.postJson(BASE + "/gmail-detections/password", Map.of("password", "x"), 200);

        assertEquals(0, answer.get("opened").asInt());
        assertEquals(5, answer.get("stillLocked").asInt());
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM nps_gmail_pending WHERE user_id = ? AND status = 'PENDING'",
                Integer.class, userId.toString()));
    }
}
