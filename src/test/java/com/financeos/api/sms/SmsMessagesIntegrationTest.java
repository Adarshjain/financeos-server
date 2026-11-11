package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.sms.device.PairAttemptLimiter;
import com.financeos.sms.device.SmsTokens;
import com.financeos.sms.ingest.SmsPipelineService;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Message intake, the reader and pipeline in shadow mode, the report, the list, confirm and reject. All data is invented. */
@SpringBootTest
@AutoConfigureMockMvc
class SmsMessagesIntegrationTest {

    private static final String HSBC = "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been debited with INR %s- on 08NOV as per your request . Your avl Bal is %s .";
    private static final String HSBC_UPI = "INR %s is paid from HSBC account XXXXXX0789 to Test Payee on 08-Nov-26 with ref %s. If this is not done by you, call 1800000000 to report.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SmsPipelineService pipeline;
    @Autowired private PairAttemptLimiter limiter;
    @Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private User user;
    private User other;
    private String token;
    private String otherToken;
    private UUID account;
    private UUID hsbcLine;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clear();
        String email = "sms-msgs-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        token = pair(api);
        String otherEmail = "sms-msgs-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        other = userRepository.findByEmail(otherEmail).orElseThrow();
        otherToken = pair(otherApi);

        account = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Test Account",
                "financialPosition", "asset", "excludeFromNetAsset", false));
        summary(token, List.of(line("HSBCIN", "789", null), line("HSBCIN", "0789", null), line("CREDWL", null, null), line("MOBIKW", null, null)));
        // HSBC texts show 3 digits in one format and 4 in another: two lines, one account
        JsonNode lines = api.getJson("/api/v1/sms/lines", 200).get("unassigned");
        for (JsonNode l : lines) {
            if (l.get("sender").asText().equals("HSBCIN")) {
                UUID id = UUID.fromString(l.get("id").asText());
                if (l.get("ending").asText().equals("789")) hsbcLine = id;
                turnOn(api, id, account, "2026-11-01");
            }
        }
        turnOn(api, lineId(api, "CREDWL"), account, "2026-11-01");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), other.getId()));
        limiter.clear();
    }

    // ---------------------------------------------------------------- helpers

    private String pair(ApiTestClient client) throws Exception {
        String code = client.postJson("/api/v1/sms/pairing-code", null, 200).get("code").asText();
        String body = mockMvc.perform(post("/api/v1/sms/pair").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("code", code, "deviceName", "Test phone"))))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    private Map<String, Object> line(String sender, String ending, String family) {
        Map<String, Object> m = new HashMap<>();
        m.put("sender", sender);
        m.put("ending", ending);
        m.put("family", family);
        m.put("msgCount", 5);
        m.put("txnCount", 4);
        m.put("firstMsgAt", "2026-01-05T04:00:00Z");
        m.put("lastMsgAt", "2026-11-01T04:00:00Z");
        return m;
    }

    private MockHttpServletResponse summaryRaw(String tok, List<Map<String, Object>> lines) throws Exception {
        return mockMvc.perform(post("/api/v1/sms/device/summary").header("Authorization", "Bearer " + tok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("lines", lines,
                                "skipped", Map.of("personal", 1, "otp", 2, "promo", 3, "unknownSender", 4)))))
                .andReturn().getResponse();
    }

    private void summary(String tok, List<Map<String, Object>> lines) throws Exception {
        assertThat(summaryRaw(tok, lines).getStatus()).isEqualTo(200);
    }

    private UUID lineId(ApiTestClient client, String sender) throws Exception {
        JsonNode view = client.getJson("/api/v1/sms/lines", 200);
        List<JsonNode> all = new ArrayList<>();
        view.get("unassigned").forEach(all::add);
        view.get("notMine").forEach(all::add);
        view.get("accounts").forEach(a -> a.get("lines").forEach(all::add));
        return all.stream().filter(l -> l.get("sender").asText().equals(sender)).findFirst()
                .map(l -> UUID.fromString(l.get("id").asText())).orElseThrow();
    }

    private void turnOn(ApiTestClient client, UUID line, UUID accountId, String syncFrom) throws Exception {
        client.postJson("/api/v1/sms/lines/" + line + "/on", Map.of("accountId", accountId.toString(), "syncFrom", syncFrom), 200);
    }

    private Map<String, Object> msg(String sender, String body, String receivedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("sender", sender);
        m.put("body", body);
        m.put("receivedAt", receivedAt);
        m.put("simSlot", 1);
        java.time.Instant at = java.time.Instant.parse(receivedAt);
        String core = com.financeos.sms.line.SenderCatalogue.normaliseSender(sender);
        m.put("fingerprint", SmsTokens.sha256(core + "\n" + at.toEpochMilli() + "\n" + body));
        return m;
    }

    private MockHttpServletResponse sendRaw(String tok, List<Map<String, Object>> messages) throws Exception {
        return mockMvc.perform(post("/api/v1/sms/device/messages").header("Authorization", "Bearer " + tok)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("messages", messages))))
                .andReturn().getResponse();
    }

    private JsonNode send(String tok, List<Map<String, Object>> messages) throws Exception {
        MockHttpServletResponse res = sendRaw(tok, messages);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return mapper.readTree(res.getContentAsString()).get("results");
    }

    private long stored(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sms_messages WHERE user_id = ?", Long.class, userId.toString());
    }

    private Map<String, Object> row(String fingerprint) {
        return jdbc.queryForMap("SELECT outcome, read_status, duplicate_of, transaction_id, id FROM sms_messages WHERE fingerprint = ?", fingerprint);
    }

    private String hsbc(String amt, String bal) {
        return HSBC.formatted(amt, bal);
    }

    private void manualTxn(String amount, LocalDate date) {
        Account a = accountRepository.findById(account).orElseThrow();
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(a);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setCategoryKey("food");
        t.setReviewType(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        transactionRepository.save(t);
    }

    private long transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ?", Long.class, user.getId().toString());
    }

    // ---------------------------------------------------------------- intake

    @Test
    void skippedCountsAreStoredOnTheDevice() {
        Map<String, Object> d = jdbc.queryForMap("SELECT skipped_personal, skipped_otp, skipped_promo, skipped_unknown_sender FROM sms_devices WHERE user_id = ?",
                user.getId().toString());
        assertThat(d.values()).extracting(Object::toString).containsExactly("1", "2", "3", "4");
    }

    @Test
    void acceptedMessageIsStoredEncryptedAndAResendIsADuplicate() throws Exception {
        Map<String, Object> m = msg("VM-HSBCIN-S", hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        JsonNode first = send(token, List.of(m));
        assertThat(first.get(0).get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(first.get(0).get("reason").isNull()).isTrue();
        assertThat(first.get(0).get("fingerprint").asText()).isEqualTo(m.get("fingerprint"));

        Map<String, Object> stored = jdbc.queryForMap("SELECT body, account_id, line_id, sender, read_status FROM sms_messages WHERE user_id = ?", user.getId().toString());
        assertThat(stored.get("body").toString()).doesNotContain("HSBC").doesNotContain("250.00");
        assertThat(stored.get("account_id")).isEqualTo(account.toString());
        assertThat(stored.get("line_id")).isEqualTo(hsbcLine.toString());
        assertThat(stored.get("sender")).isEqualTo("HSBCIN");
        assertThat(stored.get("read_status")).isEqualTo("READ");

        assertThat(send(token, List.of(m)).get(0).get("status").asText()).isEqualTo("DUPLICATE");
        assertThat(stored(user.getId())).isEqualTo(1);
    }

    @Test
    void resultsComeInRequestOrderWithReasonsAndNoTextStoredForRejects() throws Exception {
        List<Map<String, Object>> batch = List.of(
                msg("HSBCIN", hsbc("10.00", "90.00"), "2026-11-08T05:00:00Z"),                          // accepted
                msg("MOBIKW", "Rs.5 has been debited from your MobiKwik wallet. Remaining balance: Rs.9.", "2026-11-08T05:00:00Z"), // line off
                msg("HDFCBK", "Rs 10 debited from A/c XX1234", "2026-11-08T05:00:00Z"),               // no line
                msg("HSBCIN", hsbc("11.00", "89.00"), "2026-10-31T05:00:00Z"),                          // before 1 Nov start
                msg("56767", "Rs 10 debited XX1234", "2026-11-08T05:00:00Z"),                           // all digits: personal
                msg("HSBCIN", "x".repeat(2001), "2026-11-08T05:00:00Z"),                                // too long
                msg("HSBCIN", "Your OTP is 123456 A/c 123-456***-789", "2026-11-08T05:00:00Z"),         // otp
                msg("VM-HSBCIN-P", hsbc("12.00", "88.00"), "2026-11-08T05:00:00Z"));                    // promo
        JsonNode results = send(token, batch);
        assertThat(results).hasSize(8);
        String[] status = {"ACCEPTED", "REJECTED", "REJECTED", "REJECTED", "REJECTED", "REJECTED", "REJECTED", "REJECTED"};
        String[] reason = {null, "LINE_OFF", "LINE_OFF", "BEFORE_START", "BAD_MESSAGE", "BAD_MESSAGE", "BAD_MESSAGE", "BAD_MESSAGE"};
        for (int i = 0; i < 8; i++) {
            assertThat(results.get(i).get("status").asText()).as("status " + i).isEqualTo(status[i]);
            assertThat(results.get(i).get("reason").isNull() ? null : results.get(i).get("reason").asText()).as("reason " + i).isEqualTo(reason[i]);
        }
        assertThat(stored(user.getId())).isEqualTo(1);
    }

    @Test
    void nullSyncFromHasNoLowerBound() throws Exception {
        UUID mobikw = lineId(api, "MOBIKW");
        // turn on without a start date: the server defaults it, so clear it directly to model a null start
        turnOn(api, mobikw, account, "2026-11-01");
        jdbc.update("UPDATE sms_lines SET sync_from = NULL WHERE id = ?", mobikw.toString());
        JsonNode r = send(token, List.of(msg("MOBIKW", "Rs.5.00 has been debited from your MobiKwik wallet. Remaining balance: Rs.9.00. Not you?", "2020-01-01T05:00:00Z")));
        assertThat(r.get(0).get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void startDateIsJudgedInTheAppTimeZone() throws Exception {
        // 2026-11-01 00:00 IST is 2026-10-31T18:30Z
        JsonNode r = send(token, List.of(
                msg("HSBCIN", hsbc("20.00", "80.00"), "2026-10-31T18:29:59Z"),
                msg("HSBCIN", hsbc("21.00", "79.00"), "2026-10-31T18:30:00Z")));
        assertThat(r.get(0).get("reason").asText()).isEqualTo("BEFORE_START");
        assertThat(r.get(1).get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void pausedDeviceGets409AndNothingIsStored() throws Exception {
        UUID deviceId = UUID.fromString(api.getJson("/api/v1/sms/devices", 200).get(0).get("id").asText());
        api.postJson("/api/v1/sms/devices/" + deviceId + "/pause", null, 204);
        MockHttpServletResponse res = sendRaw(token, List.of(msg("HSBCIN", hsbc("10.00", "90.00"), "2026-11-08T05:00:00Z")));
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(stored(user.getId())).isZero();
        api.postJson("/api/v1/sms/devices/" + deviceId + "/resume", null, 204);
        assertThat(send(token, List.of(msg("HSBCIN", hsbc("10.00", "90.00"), "2026-11-08T05:00:00Z"))).get(0).get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void moreThan200MessagesIsRefusedAndAnotherUsersLineIsNeverWrittenTo() throws Exception {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(msg("HSBCIN", hsbc("1.00", "9.00") + i, "2026-11-08T05:00:00Z"));
        assertThat(sendRaw(token, many).getStatus()).isEqualTo(400);

        // the other user has no line for this sender: the same text is rejected for them
        JsonNode r = send(otherToken, List.of(msg("HSBCIN", hsbc("10.00", "90.00"), "2026-11-08T05:00:00Z")));
        assertThat(r.get(0).get("reason").asText()).isEqualTo("LINE_OFF");
        assertThat(stored(other.getId())).isZero();
        assertThat(stored(user.getId())).isZero();
    }

    @Test
    void deviceTokenIsRequired() throws Exception {
        assertThat(mockMvc.perform(post("/api/v1/sms/device/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[]}")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- pipeline

    @Test
    void secondAlertWithADifferentTemplateIsADuplicate() throws Exception {
        Map<String, Object> a = msg("HSBCIN", hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> b = msg("HSBCIN", HSBC_UPI.formatted("250.00", "612345678901"), "2026-11-08T05:01:00Z");
        send(token, List.of(a));
        send(token, List.of(b));
        Map<String, Object> first = row((String) a.get("fingerprint"));
        Map<String, Object> second = row((String) b.get("fingerprint"));
        assertThat(first.get("outcome")).isEqualTo("SHADOW_CREATE");
        assertThat(second.get("outcome")).isEqualTo("DUPLICATE");
        assertThat(second.get("duplicate_of")).isEqualTo(first.get("id"));
    }

    @Test
    void secondAlertInTheSameCallSeesTheFirstEvenIfListedLater() throws Exception {
        Map<String, Object> later = msg("HSBCIN", HSBC_UPI.formatted("260.00", "612345678902"), "2026-11-08T05:01:00Z");
        Map<String, Object> earlier = msg("HSBCIN", hsbc("260.00", "900.00"), "2026-11-08T05:00:00Z");
        send(token, List.of(later, earlier));
        assertThat(row((String) earlier.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_CREATE");
        assertThat(row((String) later.get("fingerprint")).get("outcome")).isEqualTo("DUPLICATE");
    }

    @Test
    void sameReferenceIsADuplicateEvenWithAnotherAmount() throws Exception {
        Map<String, Object> a = msg("HSBCIN", HSBC_UPI.formatted("300.00", "612345678903"), "2026-11-08T05:00:00Z");
        Map<String, Object> b = msg("HSBCIN", HSBC_UPI.formatted("300.00", "612345678903") + " ", "2026-11-09T05:00:00Z");
        send(token, List.of(a));
        send(token, List.of(b));
        assertThat(row((String) b.get("fingerprint")).get("outcome")).isEqualTo("DUPLICATE");
    }

    @Test
    void sameAmountWithBalancesThatDifferIsNotASecondAlert() throws Exception {
        Map<String, Object> a = msg("HSBCIN", hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        String card = "HSBC:Thank you for using HSBC Debit Card XXXXX0789 for INR 250.00 on 08NOV at Test Store .Your available bal is INR 500.00 .To report fraudulent transaction call 1800000000 (local) Or 1800000001 (overseas).";
        Map<String, Object> b = msg("HSBCIN", card, "2026-11-08T06:00:00Z");
        send(token, List.of(a, b));
        assertThat(row((String) b.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_CREATE");
    }

    @Test
    void shadowAttachWhenTheLedgerHasItAndShadowCreateWhenNotAndNothingIsPosted() throws Exception {
        manualTxn("250.00", LocalDate.of(2026, 11, 8));
        long before = transactionCount();
        Map<String, Object> attach = msg("HSBCIN", hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> create = msg("HSBCIN", hsbc("777.00", "223.00"), "2026-11-08T05:30:00Z");
        send(token, List.of(attach, create));
        Map<String, Object> a = row((String) attach.get("fingerprint"));
        assertThat(a.get("outcome")).isEqualTo("SHADOW_ATTACH");
        assertThat(a.get("transaction_id")).isNotNull();
        Map<String, Object> c = row((String) create.get("fingerprint"));
        assertThat(c.get("outcome")).isEqualTo("SHADOW_CREATE");
        assertThat(c.get("transaction_id")).isNull();
        assertThat(transactionCount()).isEqualTo(before);
    }

    @Test
    void needsConfirmAndCouldNotReadGetNoOutcome() throws Exception {
        Map<String, Object> n = msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z");
        Map<String, Object> c = msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 debited and credited again", "2026-11-08T05:01:00Z");
        Map<String, Object> i = msg("HSBCIN", "A/c 123-456***-789 is active, welcome", "2026-11-08T05:02:00Z");
        send(token, List.of(n, c, i));
        assertThat(row((String) n.get("fingerprint"))).containsEntry("read_status", "NEEDS_CONFIRM").containsEntry("outcome", null);
        assertThat(row((String) c.get("fingerprint"))).containsEntry("read_status", "COULD_NOT_READ").containsEntry("outcome", null);
        assertThat(row((String) i.get("fingerprint"))).containsEntry("read_status", "IGNORED").containsEntry("outcome", null);
    }

    @Test
    void replayRebuildsOutcomesAndKeepsTheOwnersDecisions() throws Exception {
        Map<String, Object> a = msg("HSBCIN", hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> b = msg("HSBCIN", HSBC_UPI.formatted("250.00", "612345678904"), "2026-11-08T05:01:00Z");
        Map<String, Object> n = msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T06:00:00Z");
        send(token, List.of(a, b, n));
        String id = (String) row((String) n.get("fingerprint")).get("id");
        api.postJson("/api/v1/sms/messages/" + id + "/confirm", Map.of(), 200);

        jdbc.update("UPDATE sms_messages SET outcome = NULL, duplicate_of = NULL WHERE user_id = ?", user.getId().toString());
        jdbc.update("UPDATE sms_messages SET read_status = 'IGNORED', amount = NULL WHERE fingerprint = ?", a.get("fingerprint"));
        int run = pipeline.replay(user.getId());

        assertThat(run).isEqualTo(3);
        assertThat(row((String) a.get("fingerprint"))).containsEntry("outcome", "SHADOW_CREATE").containsEntry("read_status", "READ");
        assertThat(row((String) b.get("fingerprint"))).containsEntry("outcome", "DUPLICATE");
        // confirmed by the owner: kept as READ, re-decided
        assertThat(row((String) n.get("fingerprint"))).containsEntry("read_status", "READ").containsEntry("outcome", "SHADOW_CREATE");
    }

    // ---------------------------------------------------------------- web: report, list, confirm, reject

    @Test
    void reportCountsAlertsAndTheFlag() throws Exception {
        // CRED chain: 1000 -> debit 100 -> 900 -> debit 100 -> 700 (a message is missing)
        String c1 = "Update from CRED wallet: You have successfully paid Rs.100.00 and your updated wallet balance is Rs.900.00. For any queries or support, call 1800000000.";
        String c2 = "Update from CRED wallet: You have successfully paid Rs.100.00 and your updated wallet balance is Rs.700.00. For any queries or support, call 1800000000.";
        send(token, List.of(msg("CREDWL", c1, "2026-11-05T05:00:00Z"), msg("CREDWL", c2, "2026-11-06T05:00:00Z"),
                msg("HSBCIN", hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z"),
                msg("HSBCIN", HSBC_UPI.formatted("250.00", "612345678905"), "2026-11-08T05:01:00Z"),
                msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:02:00Z"),
                msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 debited and credited again", "2026-11-08T05:03:00Z"),
                msg("HSBCIN", "A/c 123-456***-789 is active, welcome", "2026-11-08T05:04:00Z")));

        JsonNode report = api.getJson("/api/v1/sms/report", 200);
        assertThat(report.get("postingEnabled").asBoolean()).isFalse();
        JsonNode t = report.get("totals");
        assertThat(t.get("stored").asInt()).isEqualTo(7);
        assertThat(t.get("read").asInt()).isEqualTo(4);
        assertThat(t.get("needsConfirm").asInt()).isEqualTo(1);
        assertThat(t.get("couldNotRead").asInt()).isEqualTo(1);
        assertThat(t.get("ignored").asInt()).isEqualTo(1);
        assertThat(t.get("wouldCreate").asInt()).isEqualTo(3);
        assertThat(t.get("wouldAttach").asInt()).isZero();
        assertThat(t.get("created").asInt()).isZero();
        assertThat(t.get("duplicates").asInt()).isEqualTo(1);

        assertThat(report.get("accounts")).hasSize(1);
        JsonNode acc = report.get("accounts").get(0);
        assertThat(acc.get("accountId").asText()).isEqualTo(account.toString());
        assertThat(acc.get("name").asText()).isEqualTo("Test Account");
        assertThat(acc.get("couldNotRead").asInt()).isEqualTo(1);
        assertThat(acc.get("firstMessageAt").asText()).startsWith("2026-11-05");
        List<String> kinds = new ArrayList<>();
        acc.get("alerts").forEach(x -> kinds.add(x.get("kind").asText()));
        assertThat(kinds).contains("COULD_NOT_READ", "BALANCE_GAP");
        List<String> gaps = new ArrayList<>();
        for (JsonNode x : acc.get("alerts")) if (x.get("kind").asText().equals("BALANCE_GAP")) gaps.add(x.get("message").asText());
        assertThat(gaps).contains("A message may be missing around 06/11/2026");
    }

    @Test
    void reportOfAnotherUserIsEmptyAndConsistentChainsRaiseNoGap() throws Exception {
        String c1 = "Update from CRED wallet: You have successfully paid Rs.100.00 and your updated wallet balance is Rs.900.00. For any queries or support, call 1800000000.";
        String c2 = "Update from CRED wallet: You have successfully paid Rs.100.00 and your updated wallet balance is Rs.800.00. For any queries or support, call 1800000000.";
        send(token, List.of(msg("CREDWL", c1, "2026-11-05T05:00:00Z"), msg("CREDWL", c2, "2026-11-06T05:00:00Z")));
        JsonNode mine = api.getJson("/api/v1/sms/report", 200);
        assertThat(mine.get("accounts").get(0).get("alerts")).isEmpty();
        JsonNode theirs = otherApi.getJson("/api/v1/sms/report", 200);
        assertThat(theirs.get("totals").get("stored").asInt()).isZero();
        assertThat(theirs.get("accounts")).isEmpty();
    }

    @Test
    void messagesListIsPagedNewestFirstFilteredAndDecrypted() throws Exception {
        send(token, List.of(
                msg("HSBCIN", hsbc("10.00", "90.00"), "2026-11-05T05:00:00Z"),
                msg("HSBCIN", hsbc("20.00", "70.00"), "2026-11-06T05:00:00Z"),
                msg("HSBCIN", hsbc("30.00", "40.00"), "2026-11-07T05:00:00Z"),
                msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z")));
        JsonNode p0 = api.getJson("/api/v1/sms/messages?page=0&size=2", 200);
        assertThat(p0.get("total").asInt()).isEqualTo(4);
        assertThat(p0.get("page").asInt()).isZero();
        assertThat(p0.get("size").asInt()).isEqualTo(2);
        assertThat(p0.get("items")).hasSize(2);
        assertThat(p0.get("items").get(0).get("receivedAt").asText()).startsWith("2026-11-08");
        assertThat(p0.get("items").get(1).get("amount").decimalValue()).isEqualByComparingTo("30.00");
        JsonNode p1 = api.getJson("/api/v1/sms/messages?page=1&size=2", 200);
        assertThat(p1.get("items").get(1).get("amount").decimalValue()).isEqualByComparingTo("10.00");
        JsonNode item = p1.get("items").get(1);
        assertThat(item.get("body").asText()).contains("HSBC A/c 123-456***-789");
        assertThat(item.get("provider").asText()).isEqualTo("HSBC");
        assertThat(item.get("accountName").asText()).isEqualTo("Test Account");
        assertThat(item.get("direction").asText()).isEqualTo("debit");
        assertThat(item.get("readStatus").asText()).isEqualTo("READ");
        assertThat(item.get("balanceAfter").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(item.get("outcome").asText()).isEqualTo("SHADOW_CREATE");

        JsonNode needs = api.getJson("/api/v1/sms/messages?status=NEEDS_CONFIRM", 200);
        assertThat(needs.get("total").asInt()).isEqualTo(1);
        assertThat(needs.get("items").get(0).get("kind").asText()).isEqualTo("TRANSACTION");
        assertThat(api.getJson("/api/v1/sms/messages?status=READ&accountId=" + account, 200).get("total").asInt()).isEqualTo(3);
        assertThat(api.getJson("/api/v1/sms/messages?accountId=" + UUID.randomUUID(), 200).get("total").asInt()).isZero();
        api.getJson("/api/v1/sms/messages?status=BOGUS", 400);
        assertThat(otherApi.getJson("/api/v1/sms/messages", 200).get("total").asInt()).isZero();
    }

    @Test
    void confirmAppliesOverridesAndRunsThePipeline() throws Exception {
        send(token, List.of(msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z")));
        String id = (String) jdbc.queryForMap("SELECT id FROM sms_messages WHERE user_id = ?", user.getId().toString()).get("id");
        JsonNode view = api.postJson("/api/v1/sms/messages/" + id + "/confirm",
                Map.of("amount", 120.5, "direction", "credit", "txnDate", "2026-11-07", "counterparty", "Test Shop"), 200);
        assertThat(view.get("readStatus").asText()).isEqualTo("READ");
        assertThat(view.get("amount").decimalValue()).isEqualByComparingTo("120.50");
        assertThat(view.get("direction").asText()).isEqualTo("credit");
        assertThat(view.get("txnDate").asText()).isEqualTo("2026-11-07");
        assertThat(view.get("counterparty").asText()).isEqualTo("Test Shop");
        assertThat(view.get("outcome").asText()).isEqualTo("SHADOW_CREATE");
        // a confirmed message cannot be confirmed or rejected again
        api.postJson("/api/v1/sms/messages/" + id + "/confirm", Map.of(), 409);
        api.postJson("/api/v1/sms/messages/" + id + "/reject", null, 409);
    }

    @Test
    void confirmWithoutOverridesUsesWhatWasRead() throws Exception {
        send(token, List.of(msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z")));
        String id = (String) jdbc.queryForMap("SELECT id FROM sms_messages WHERE user_id = ?", user.getId().toString()).get("id");
        JsonNode view = api.postJson("/api/v1/sms/messages/" + id + "/confirm", null, 200);
        assertThat(view.get("amount").decimalValue()).isEqualByComparingTo("99.00");
        assertThat(view.get("direction").asText()).isEqualTo("debit");
        assertThat(view.get("outcome").asText()).isEqualTo("SHADOW_CREATE");
    }

    @Test
    void couldNotReadNeedsTheOwnerToSupplyTheMissingParts() throws Exception {
        send(token, List.of(msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 debited and credited again", "2026-11-08T05:00:00Z")));
        String id = (String) jdbc.queryForMap("SELECT id FROM sms_messages WHERE user_id = ?", user.getId().toString()).get("id");
        api.postJson("/api/v1/sms/messages/" + id + "/confirm", Map.of(), 400);
        JsonNode view = api.postJson("/api/v1/sms/messages/" + id + "/confirm", Map.of("amount", 99, "direction", "debit"), 200);
        assertThat(view.get("readStatus").asText()).isEqualTo("READ");
        assertThat(view.get("txnDate").asText()).isEqualTo("2026-11-08");
        api.postJson("/api/v1/sms/messages/" + id + "/confirm", Map.of("direction", "sideways"), 409);
    }

    @Test
    void rejectMakesItIgnoredWithNoOutcome() throws Exception {
        send(token, List.of(msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z")));
        String id = (String) jdbc.queryForMap("SELECT id FROM sms_messages WHERE user_id = ?", user.getId().toString()).get("id");
        JsonNode view = api.postJson("/api/v1/sms/messages/" + id + "/reject", null, 200);
        assertThat(view.get("readStatus").asText()).isEqualTo("IGNORED");
        assertThat(view.get("outcome").isNull()).isTrue();
        // a replay keeps the owner's decision
        pipeline.replay(user.getId());
        assertThat(jdbc.queryForMap("SELECT read_status FROM sms_messages WHERE id = ?", id)).containsEntry("read_status", "IGNORED");
    }

    @Test
    void anotherUsersMessageIsNotFound() throws Exception {
        send(token, List.of(msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z")));
        String id = (String) jdbc.queryForMap("SELECT id FROM sms_messages WHERE user_id = ?", user.getId().toString()).get("id");
        otherApi.postJson("/api/v1/sms/messages/" + id + "/confirm", Map.of("amount", 1, "direction", "debit"), 404);
        otherApi.postJson("/api/v1/sms/messages/" + id + "/reject", null, 404);
        assertThat(jdbc.queryForMap("SELECT read_status FROM sms_messages WHERE id = ?", id)).containsEntry("read_status", "NEEDS_CONFIRM");
    }

    // ---------------------------------------------------------------- review fixes

    private static final String UPI_CREDIT = "Your HSBC Acc XXXXXX0789 is credited for INR %s on 08-Nov-26 from tester@examplebank. UPI Ref No %s";

    @Test
    void twoEqualPaymentsEachTextedInTwoFormatsAreTwoKeptAndTwoDuplicates() throws Exception {
        Map<String, Object> a1 = msg("HSBCIN", hsbc("250.00", "750.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> b1 = msg("HSBCIN", HSBC_UPI.formatted("250.00", "612345678911"), "2026-11-08T05:01:00Z");
        Map<String, Object> a2 = msg("HSBCIN", hsbc("250.00", "500.00"), "2026-11-08T06:00:00Z");
        Map<String, Object> b2 = msg("HSBCIN", HSBC_UPI.formatted("250.00", "612345678912"), "2026-11-08T06:01:00Z");
        send(token, List.of(b2, a2, b1, a1));
        assertThat(row((String) a1.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_CREATE");
        assertThat(row((String) a2.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_CREATE");
        Map<String, Object> d1 = row((String) b1.get("fingerprint"));
        Map<String, Object> d2 = row((String) b2.get("fingerprint"));
        assertThat(d1.get("outcome")).isEqualTo("DUPLICATE");
        assertThat(d2.get("outcome")).isEqualTo("DUPLICATE");
        assertThat(d1.get("duplicate_of")).isEqualTo(row((String) a1.get("fingerprint")).get("id"));
        assertThat(d2.get("duplicate_of")).isEqualTo(row((String) a2.get("fingerprint")).get("id"));
    }

    @Test
    void differentReferencesOnEqualAmountsAreNotSecondAlerts() throws Exception {
        Map<String, Object> a = msg("HSBCIN", HSBC_UPI.formatted("90.00", "612345678913"), "2026-11-08T05:00:00Z");
        String neft = "HSBC: A/c 123-456***-789 is debited with INR 90.00- on 08NOV with UTR TESTN00000001 as NEFT via Online to Sample Bank A/c ***5566 Test Name . Avl Bal is INR 910.00 . Report fraud 1800000000/1800000001.";
        Map<String, Object> b = msg("HSBCIN", neft, "2026-11-08T05:30:00Z");
        send(token, List.of(a, b));
        assertThat(row((String) b.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_CREATE");
    }

    @Test
    void aRefundQuotingTheOriginalReferenceIsNotDropped() throws Exception {
        Map<String, Object> pay = msg("HSBCIN", HSBC_UPI.formatted("100.00", "612345678910"), "2026-11-08T05:00:00Z");
        Map<String, Object> refund = msg("HSBCIN", UPI_CREDIT.formatted("100.00", "612345678910"), "2026-11-08T07:00:00Z");
        Map<String, Object> again = msg("HSBCIN", HSBC_UPI.formatted("100.00", "612345678910") + " .", "2026-11-08T08:00:00Z");
        send(token, List.of(pay, refund, again));
        assertThat(row((String) pay.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_CREATE");
        assertThat(row((String) refund.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_CREATE");
        // the same debit quoted again is still the same payment
        assertThat(row((String) again.get("fingerprint")).get("outcome")).isEqualTo("DUPLICATE");
    }

    @Test
    void aTransactionClaimedByOneMessageCannotBeClaimedByAnother() throws Exception {
        manualTxn("250.00", LocalDate.of(2026, 11, 8));
        Map<String, Object> first = msg("HSBCIN", hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> second = msg("HSBCIN", hsbc("250.00", "750.00"), "2026-11-08T07:00:00Z");
        send(token, List.of(first));
        send(token, List.of(second));
        assertThat(row((String) first.get("fingerprint")).get("outcome")).isEqualTo("SHADOW_ATTACH");
        Map<String, Object> s = row((String) second.get("fingerprint"));
        assertThat(s.get("outcome")).isEqualTo("SHADOW_CREATE");
        assertThat(s.get("transaction_id")).isNull();
    }

    @Test
    void balancesChainPerLineNotPerAccount() throws Exception {
        String cred1 = "Update from CRED wallet: You have successfully paid Rs.100.00 and your updated wallet balance is Rs.900.00. For any queries or support, call 1800000000.";
        String cred2 = "Update from CRED wallet: You have successfully paid Rs.100.00 and your updated wallet balance is Rs.800.00. For any queries or support, call 1800000000.";
        send(token, List.of(msg("CREDWL", cred1, "2026-11-05T05:00:00Z"),
                msg("HSBCIN", hsbc("250.00", "5,000.00"), "2026-11-05T06:00:00Z"),
                msg("CREDWL", cred2, "2026-11-06T05:00:00Z"),
                msg("HSBCIN", hsbc("250.00", "4,750.00"), "2026-11-06T06:00:00Z")));
        JsonNode alerts = api.getJson("/api/v1/sms/report", 200).get("accounts").get(0).get("alerts");
        for (JsonNode x : alerts) assertThat(x.get("kind").asText()).isNotEqualTo("BALANCE_GAP");
    }

    @Test
    void simSlotIsClampedAndATooLargeAmountDoesNotFailTheBatch() throws Exception {
        Map<String, Object> ok = msg("HSBCIN", hsbc("10.00", "90.00"), "2026-11-08T05:00:00Z");
        ok.put("simSlot", 1);
        Map<String, Object> wild = msg("HSBCIN", hsbc("11.00", "79.00"), "2026-11-08T05:01:00Z");
        wild.put("simSlot", 500);
        Map<String, Object> huge = msg("HSBCIN", hsbc("12345678901234.00", "79.00"), "2026-11-08T05:02:00Z");
        JsonNode r = send(token, List.of(ok, wild, huge));
        for (JsonNode x : r) assertThat(x.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT sim_slot FROM sms_messages WHERE fingerprint = ?", Integer.class, ok.get("fingerprint"))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sim_slot FROM sms_messages WHERE fingerprint = ?", Integer.class, wild.get("fingerprint"))).isNull();
        assertThat(row((String) huge.get("fingerprint"))).containsEntry("read_status", "COULD_NOT_READ");
    }

    @Test
    void confirmRefusesAnAmountTheColumnCannotHold() throws Exception {
        send(token, List.of(msg("HSBCIN", "A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z")));
        String id = (String) jdbc.queryForMap("SELECT id FROM sms_messages WHERE user_id = ?", user.getId().toString()).get("id");
        api.postJson("/api/v1/sms/messages/" + id + "/confirm", Map.of("amount", new BigDecimal("12345678901234")), 400);
        assertThat(jdbc.queryForMap("SELECT read_status FROM sms_messages WHERE id = ?", id)).containsEntry("read_status", "NEEDS_CONFIRM");
    }

    @Test
    void deviceConfigCarriesTheAppZone() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(res.getContentAsString()).get("zone").asText()).isEqualTo(com.financeos.core.time.AppTime.zone().getId());
    }

    @Test
    void deviceCanRevokeItsOwnTokenAndThenGets401() throws Exception {
        assertThat(mockMvc.perform(post("/api/v1/sms/device/revoke").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(mockMvc.perform(post("/api/v1/sms/device/revoke").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(api.getJson("/api/v1/sms/devices", 200).get(0).get("revokedAt").isNull()).isFalse();
        // another user's device is untouched
        assertThat(mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + otherToken))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void replayTakesTheUserLockAndStaysDeterministic() throws Exception {
        send(token, List.of(msg("HSBCIN", hsbc("250.00", "750.00"), "2026-11-08T05:00:00Z"),
                msg("HSBCIN", HSBC_UPI.formatted("250.00", "612345678914"), "2026-11-08T05:01:00Z")));
        pipeline.replay(user.getId());
        pipeline.replay(user.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_messages WHERE user_id = ? AND outcome = 'DUPLICATE'", Long.class, user.getId().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_messages WHERE user_id = ? AND outcome = 'SHADOW_CREATE'", Long.class, user.getId().toString())).isEqualTo(1);
    }

    @Test
    void statementsPerMessageStayASmallConstant() throws Exception {
        SessionFactory sf = entityManagerFactory.unwrap(SessionFactory.class);
        sf.getStatistics().setStatisticsEnabled(true);
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            batch.add(msg("HSBCIN", hsbc((100 + i) + ".00", "900.00") + " n" + i, "2026-11-08T0" + i + ":00:00Z"));
        }
        send(token, List.of(msg("HSBCIN", hsbc("5.00", "95.00"), "2026-11-07T05:00:00Z"))); // warm caches
        sf.getStatistics().clear();
        send(token, batch);
        long perMessage = sf.getStatistics().getPrepareStatementCount() / batch.size();
        System.out.println("SMS statements per message: " + perMessage);
        assertThat(perMessage).isLessThanOrEqualTo(10);
    }

    // ---------------------------------------------------------------- bulk not-mine

    @Test
    void bulkNotMineMarksEveryLineInOneCall() throws Exception {
        summary(token, List.of(line("HDFCBK", "1234", null), line("AXISBK", "4321", null)));
        UUID a = lineId(api, "HDFCBK");
        UUID b = lineId(api, "AXISBK");
        api.postJson("/api/v1/sms/lines/not-mine", Map.of("ids", List.of(a.toString(), b.toString())), 204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_lines WHERE user_id = ? AND state = 'NOT_MINE'", Long.class, user.getId().toString())).isEqualTo(2);
        api.postJson("/api/v1/sms/lines/" + a + "/undo", null, 200);
        assertThat(jdbc.queryForObject("SELECT state FROM sms_lines WHERE id = ?", String.class, a.toString())).isEqualTo("OFF");
    }

    @Test
    void bulkNotMineWithAnotherUsersIdChangesNothing() throws Exception {
        summary(token, List.of(line("HDFCBK", "1234", null)));
        summary(otherToken, List.of(line("AXISBK", "4321", null)));
        UUID mine = lineId(api, "HDFCBK");
        UUID theirs = lineId(otherApi, "AXISBK");
        api.postJson("/api/v1/sms/lines/not-mine", Map.of("ids", List.of(mine.toString(), theirs.toString())), 404);
        assertThat(jdbc.queryForObject("SELECT state FROM sms_lines WHERE id = ?", String.class, mine.toString())).isEqualTo("OFF");
        assertThat(jdbc.queryForObject("SELECT state FROM sms_lines WHERE id = ?", String.class, theirs.toString())).isEqualTo("OFF");
        api.postJson("/api/v1/sms/lines/not-mine", Map.of("ids", List.of()), 400);
    }

    @Test
    void bulkUndoReturnsEveryLineToOffAndAnotherUsersIdChangesNothing() throws Exception {
        summary(token, List.of(line("HDFCBK", "1234", null), line("AXISBK", "4321", null)));
        summary(otherToken, List.of(line("YESBNK", "5555", null)));
        UUID a = lineId(api, "HDFCBK");
        UUID b = lineId(api, "AXISBK");
        UUID theirs = lineId(otherApi, "YESBNK");
        api.postJson("/api/v1/sms/lines/not-mine", Map.of("ids", List.of(a.toString(), b.toString())), 204);

        api.postJson("/api/v1/sms/lines/undo", Map.of("ids", List.of(a.toString(), theirs.toString())), 404);
        assertThat(jdbc.queryForObject("SELECT state FROM sms_lines WHERE id = ?", String.class, a.toString())).isEqualTo("NOT_MINE");

        api.postJson("/api/v1/sms/lines/undo", Map.of("ids", List.of(a.toString(), b.toString())), 204);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_lines WHERE user_id = ? AND state = 'OFF' AND sender IN ('HDFCBK','AXISBK')", Long.class, user.getId().toString())).isEqualTo(2);
        api.postJson("/api/v1/sms/lines/undo", Map.of("ids", List.of()), 400);
    }

    @Test
    void summarySkipsAllDigitSenders() throws Exception {
        MockHttpServletResponse res = summaryRaw(token, List.of(line("56767", "1234", null), line("TESTBK", "1234", null)));
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode body = mapper.readTree(res.getContentAsString());
        assertThat(body.get("skippedLines").asInt()).isEqualTo(1);
        assertThat(body.get("lines").asInt()).isEqualTo(1);
    }
}
