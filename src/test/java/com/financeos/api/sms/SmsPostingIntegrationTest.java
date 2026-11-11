package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.financeos.sms.device.SmsLoginLimiter;
import com.financeos.sms.device.SmsTokens;
import com.financeos.sms.ingest.SmsPipelineService;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Posting for real (flag on): create, attach, idempotence, confirm, replay, the replay endpoint, the opening balance. Invented data. */
@SpringBootTest(properties = "financeos.sms.posting-enabled=true")
@AutoConfigureMockMvc
class SmsPostingIntegrationTest {

    private static final String HSBC = "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been debited with INR %s- on 08NOV as per your request . Your avl Bal is %s .";
    private static final String HSBC_UPI = "INR %s is paid from HSBC account XXXXXX0789 to Test Payee on 08-Nov-26 with ref %s. If this is not done by you, call 1800000000 to report.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SmsPipelineService pipeline;
    @Autowired private SmsLoginLimiter limiter;

    private ApiTestClient api;
    private User user;
    private String token;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clearAll();
        String email = "sms-post-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        token = pair(user.getEmail());
        account = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Test Wallet",
                "financialPosition", "asset", "excludeFromNetAsset", false));
        summary(token, List.of(line("HSBCIN", "789"), line("HSBCIN", "0789")));
        for (JsonNode l : api.getJson("/api/v1/sms/lines", 200).get("unassigned")) {
            api.postJson("/api/v1/sms/lines/" + l.get("id").asText() + "/on",
                    Map.of("accountId", account.toString(), "syncFrom", "2026-11-01"), 200);
        }
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        limiter.clearAll();
    }

    // ---------------------------------------------------------------- helpers

    private String pair(String email) throws Exception {
        return SmsTestLogin.token(mockMvc, mapper, email);
    }

    private Map<String, Object> line(String sender, String ending) {
        Map<String, Object> m = new HashMap<>();
        m.put("sender", sender);
        m.put("ending", ending);
        m.put("family", null);
        m.put("msgCount", 5);
        m.put("txnCount", 4);
        m.put("firstMsgAt", "2026-01-05T04:00:00Z");
        m.put("lastMsgAt", "2026-11-01T04:00:00Z");
        return m;
    }

    private void summary(String tok, List<Map<String, Object>> lines) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/summary").header("Authorization", "Bearer " + tok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("lines", lines,
                                "skipped", Map.of("personal", 0, "otp", 0, "promo", 0, "unknownSender", 0)))))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    private Map<String, Object> msg(String body, String receivedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("sender", "HSBCIN");
        m.put("body", body);
        m.put("receivedAt", receivedAt);
        m.put("simSlot", 1);
        java.time.Instant at = java.time.Instant.parse(receivedAt);
        m.put("fingerprint", SmsTokens.sha256("HSBCIN\n" + at.toEpochMilli() + "\n" + body));
        return m;
    }

    private void send(List<Map<String, Object>> messages) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/messages").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("messages", messages))))
                .andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
    }

    private Map<String, Object> row(Map<String, Object> message) {
        return jdbc.queryForMap("SELECT outcome, read_status, duplicate_of, transaction_id, id FROM sms_messages WHERE fingerprint = ?",
                message.get("fingerprint"));
    }

    private String hsbc(String amt, String bal) {
        return HSBC.formatted(amt, bal);
    }

    private long transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ?", Long.class, user.getId().toString());
    }

    private Transaction manualTxn(String amount, LocalDate date) {
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
        return transactionRepository.save(t);
    }

    private Transaction txnOf(Map<String, Object> message) {
        return transactionRepository.findById(UUID.fromString((String) row(message).get("transaction_id"))).orElseThrow();
    }

    // ---------------------------------------------------------------- create

    @Test
    void aReadMessageCreatesATransactionWithSourceSmsAlertAndItsMessageId() throws Exception {
        Map<String, Object> m = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(m));

        Map<String, Object> r = row(m);
        assertThat(r.get("outcome")).isEqualTo("CREATED");
        assertThat(r.get("transaction_id")).isNotNull();
        Transaction t = txnOf(m);
        assertThat(t.getSource()).isEqualTo(TransactionSource.sms_alert);
        assertThat(t.getSourceMessageId()).isEqualTo("sms:" + r.get("id"));
        assertThat(t.getAccount().getId()).isEqualTo(account);
        assertThat(t.getAmount()).isEqualByComparingTo("250.00");
        assertThat(t.getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(t.getDate()).isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void theSecondFormatOfTheSameAlertIsADuplicateAndMakesNoSecondTransaction() throws Exception {
        Map<String, Object> a = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> b = msg(HSBC_UPI.formatted("250.00", "612345678904"), "2026-11-08T05:01:00Z");
        send(List.of(a, b));
        assertThat(row(a).get("outcome")).isEqualTo("CREATED");
        assertThat(row(b).get("outcome")).isEqualTo("DUPLICATE");
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void twoEqualPaymentsWithDifferentReferencesAreTwoTransactions() throws Exception {
        Map<String, Object> a = msg(HSBC_UPI.formatted("250.00", "612345678911"), "2026-11-08T05:00:00Z");
        Map<String, Object> b = msg(HSBC_UPI.formatted("250.00", "612345678922"), "2026-11-08T05:30:00Z");
        send(List.of(a, b));
        assertThat(row(a).get("outcome")).isEqualTo("CREATED");
        assertThat(row(b).get("outcome")).isEqualTo("CREATED");
        assertThat(row(a).get("transaction_id")).isNotEqualTo(row(b).get("transaction_id"));
        assertThat(transactionCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- attach

    @Test
    void aMessageAttachesToTheManualEntryOfThePayment() throws Exception {
        Transaction manual = manualTxn("250.00", LocalDate.of(2026, 11, 8));
        Map<String, Object> m = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(m));

        Map<String, Object> r = row(m);
        assertThat(r.get("outcome")).isEqualTo("ATTACHED");
        assertThat(r.get("transaction_id")).isEqualTo(manual.getId().toString());
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(transactionRepository.findById(manual.getId()).orElseThrow().getSource()).isEqualTo(TransactionSource.manual);
    }

    @Test
    void aGenericReadingAttachesLinkOnlyAndNeverCreates() throws Exception {
        Transaction manual = manualTxn("99.00", LocalDate.of(2026, 11, 7));
        manual.setSourcedDescription("Typed note");
        transactionRepository.save(manual);
        Map<String, Object> g = msg("A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T05:00:00Z");
        Map<String, Object> none = msg("A/c 123-456***-789 Rs 77.00 spent at Test Mart", "2026-11-08T06:00:00Z");
        send(List.of(g, none));

        Map<String, Object> r = row(g);
        assertThat(r.get("read_status")).isEqualTo("READ");
        assertThat(r.get("outcome")).isEqualTo("ATTACHED");
        assertThat(r.get("transaction_id")).isEqualTo(manual.getId().toString());
        Transaction after = transactionRepository.findById(manual.getId()).orElseThrow();
        assertThat(after.getSourcedDescription()).isEqualTo("Typed note");
        assertThat(after.getDate()).isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(after.getSource()).isEqualTo(TransactionSource.manual);
        // the one without a match still asks and wrote nothing
        assertThat(row(none).get("read_status")).isEqualTo("NEEDS_CONFIRM");
        assertThat(row(none).get("outcome")).isNull();
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void oneTransactionIsNotAttachedByTwoMessages() throws Exception {
        manualTxn("250.00", LocalDate.of(2026, 11, 8));
        Map<String, Object> a = msg(HSBC_UPI.formatted("250.00", "612345678911"), "2026-11-08T05:00:00Z");
        Map<String, Object> b = msg(HSBC_UPI.formatted("250.00", "612345678922"), "2026-11-08T05:30:00Z");
        send(List.of(a, b));
        assertThat(row(a).get("outcome")).isEqualTo("ATTACHED");
        assertThat(row(b).get("outcome")).isEqualTo("CREATED");
        assertThat(transactionCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- idempotence, confirm, replay

    @Test
    void postingAMessageTwiceNeverCreatesTwoTransactions() throws Exception {
        Map<String, Object> m = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(m));
        String txnId = (String) row(m).get("transaction_id");

        // the stored outcome is lost (as if the row had not been decided): posting again finds the transaction it made
        jdbc.update("UPDATE sms_messages SET outcome = NULL, transaction_id = NULL WHERE fingerprint = ?", m.get("fingerprint"));
        pipeline.replay(user.getId());
        pipeline.replay(user.getId());

        assertThat(transactionCount()).isEqualTo(1);
        assertThat(row(m).get("outcome")).isEqualTo("CREATED");
        assertThat(row(m).get("transaction_id")).isEqualTo(txnId);
    }

    @Test
    void replayLeavesPostedMessagesAlone() throws Exception {
        Map<String, Object> m = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(m));
        String txnId = (String) row(m).get("transaction_id");
        int run = pipeline.replay(user.getId());
        assertThat(run).isZero();
        assertThat(row(m).get("transaction_id")).isEqualTo(txnId);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void confirmingAMessagePostsIt() throws Exception {
        Map<String, Object> n = msg("A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-08T06:00:00Z");
        send(List.of(n));
        assertThat(row(n).get("outcome")).isNull();
        assertThat(transactionCount()).isZero();

        JsonNode view = api.postJson("/api/v1/sms/messages/" + row(n).get("id") + "/confirm", Map.of(), 200);

        assertThat(view.get("outcome").asText()).isEqualTo("CREATED");
        assertThat(view.get("transactionId").isNull()).isFalse();
        assertThat(transactionCount()).isEqualTo(1);
        assertThat(txnOf(n).getSource()).isEqualTo(TransactionSource.sms_alert);
    }

    @Test
    void replayEndpointTurnsShadowOutcomesIntoRealOnes() throws Exception {
        Map<String, Object> create = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> attach = msg(hsbc("90.00", "910.00"), "2026-11-09T05:00:00Z");
        send(List.of(create, attach));
        // back to what posting off would have left: shadow outcomes and no transactions made by messages
        jdbc.update("UPDATE sms_messages SET outcome = 'SHADOW_CREATE', transaction_id = NULL WHERE user_id = ?", user.getId().toString());
        jdbc.update("DELETE FROM transactions WHERE user_id = ?", user.getId().toString());
        manualTxn("90.00", LocalDate.of(2026, 11, 9));
        jdbc.update("UPDATE sms_messages SET outcome = 'SHADOW_ATTACH' WHERE fingerprint = ?", attach.get("fingerprint"));
        assertThat(transactionCount()).isEqualTo(1);

        JsonNode res = api.postJson("/api/v1/sms/replay", null, 200);

        assertThat(res.get("replayed").asInt()).isEqualTo(2);
        assertThat(res.get("outcomes").get("CREATED").asInt()).isEqualTo(1);
        assertThat(res.get("outcomes").get("ATTACHED").asInt()).isEqualTo(1);
        assertThat(res.get("outcomes").get("SHADOW_CREATE").asInt()).isZero();
        assertThat(row(create).get("outcome")).isEqualTo("CREATED");
        assertThat(row(attach).get("outcome")).isEqualTo("ATTACHED");
        assertThat(transactionCount()).isEqualTo(2);
    }

    @Test
    void replayEndpointOnlyTouchesTheCallersMessages() throws Exception {
        Map<String, Object> m = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(m));
        jdbc.update("UPDATE sms_messages SET outcome = 'SHADOW_CREATE', transaction_id = NULL WHERE user_id = ?", user.getId().toString());
        jdbc.update("DELETE FROM transactions WHERE user_id = ?", user.getId().toString());

        String otherEmail = "sms-post-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        User otherUser = userRepository.findByEmail(otherEmail).orElseThrow();
        try {
            JsonNode res = other.postJson("/api/v1/sms/replay", null, 200);
            assertThat(res.get("replayed").asInt()).isZero();
            assertThat(res.get("outcomes").get("SHADOW_CREATE").asInt()).isZero();
            assertThat(row(m).get("outcome")).isEqualTo("SHADOW_CREATE");
            assertThat(transactionCount()).isZero();
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherUser.getId()));
        }
    }

    @Test
    void replayEndpointNeedsASession() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/replay")).andReturn().getResponse();
        assertThat(res.getStatus()).isIn(401, 403);
    }

    // ---------------------------------------------------------------- opening balance

    private JsonNode accountJson() throws Exception {
        return api.getJson("/api/v1/accounts/" + account, 200);
    }

    @Test
    void theFirstMessageOfAGenericAccountSetsTheOpeningBalanceFromItsBalance() throws Exception {
        send(List.of(msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z")));
        JsonNode a = accountJson();
        assertThat(a.get("openingBalance").decimalValue()).isEqualByComparingTo("1250");
        assertThat(a.get("balance").decimalValue()).isEqualByComparingTo("1000");

        send(List.of(msg(hsbc("100.00", "900.00"), "2026-11-09T05:00:00Z")));
        a = accountJson();
        assertThat(a.get("openingBalance").decimalValue()).isEqualByComparingTo("1250");
        assertThat(a.get("balance").decimalValue()).isEqualByComparingTo("900");
    }

    @Test
    void aValueTheUserSetIsNeverOverwritten() throws Exception {
        Map<String, Object> edit = new HashMap<>(Map.of("type", "generic", "name", "Test Wallet", "financialPosition", "asset",
                "excludeFromNetAsset", false, "openingBalance", 5000));
        api.putJson("/api/v1/accounts/" + account, edit, 200);
        send(List.of(msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z")));
        assertThat(accountJson().get("openingBalance").decimalValue()).isEqualByComparingTo("5000");
    }

    @Test
    void anAccountThatAlreadyHasAnEarlierTransactionKeepsNoOpeningBalance() throws Exception {
        manualTxn("40.00", LocalDate.of(2026, 11, 3));
        send(List.of(msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z")));
        assertThat(accountJson().get("openingBalance").isNull()).isTrue();
    }

    @Test
    void aMessageThatAttachesSetsNoOpeningBalance() throws Exception {
        manualTxn("250.00", LocalDate.of(2026, 11, 8));
        send(List.of(msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z")));
        assertThat(accountJson().get("openingBalance").isNull()).isTrue();
    }

    @Test
    void aMessageWithoutABalanceSetsNoOpeningBalance() throws Exception {
        send(List.of(msg(HSBC_UPI.formatted("250.00", "612345678933"), "2026-11-08T05:00:00Z")));
        assertThat(row(msg(HSBC_UPI.formatted("250.00", "612345678933"), "2026-11-08T05:00:00Z")).get("outcome")).isEqualTo("CREATED");
        assertThat(accountJson().get("openingBalance").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- description, replay never un-posts

    @Test
    void aPostedMessageWithoutACounterpartyNeverPutsItsTextInTheDescription() throws Exception {
        Map<String, Object> m = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(m));
        String d = txnOf(m).getSourcedDescription();
        assertThat(d).isNotBlank();
        assertThat(d).doesNotContainPattern("\\d");
        assertThat(d).doesNotContain("123").doesNotContain("789").doesNotContain("1,000");
    }

    @Test
    void replayNeverUnPostsAMessageEvenWhenItWouldNowBeJudgedADuplicate() throws Exception {
        Map<String, Object> a = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> b = msg(HSBC_UPI.formatted("250.00", "612345678955"), "2026-11-08T05:01:00Z");
        send(List.of(a, b));
        assertThat(row(a).get("outcome")).isEqualTo("CREATED");
        String txnId = (String) row(a).get("transaction_id");
        // the posted message is now the later one: in a fresh judgement it would be the duplicate
        jdbc.update("UPDATE sms_messages SET received_at = ? WHERE fingerprint = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-11-08T05:02:00Z")), a.get("fingerprint"));

        pipeline.replay(user.getId());

        assertThat(row(a).get("outcome")).isEqualTo("CREATED");
        assertThat(row(a).get("transaction_id")).isEqualTo(txnId);
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void replayKeepsAnAttachedMessageAttached() throws Exception {
        Transaction manual = manualTxn("250.00", LocalDate.of(2026, 11, 8));
        Map<String, Object> a = msg(hsbc("250.00", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(a));
        assertThat(row(a).get("outcome")).isEqualTo("ATTACHED");
        pipeline.replay(user.getId());
        assertThat(row(a).get("outcome")).isEqualTo("ATTACHED");
        assertThat(row(a).get("transaction_id")).isEqualTo(manual.getId().toString());
        assertThat(transactionCount()).isEqualTo(1);
    }
}
