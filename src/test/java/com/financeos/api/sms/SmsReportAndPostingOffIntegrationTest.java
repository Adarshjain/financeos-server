package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierKind;
import com.financeos.domain.account.AccountIdentifierRepository;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkCandidateLoader;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.sms.device.SmsLoginLimiter;
import com.financeos.sms.device.SmsTokens;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

/**
 * Posting off (the default) writes no transaction; the report's SILENT_LINE and BALANCE_GAP alerts; the suggestion for
 * both HSBC forms; auto-linking sees an sms_alert row. All data is invented.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmsReportAndPostingOffIntegrationTest {

    private static final String HSBC = "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been debited with INR %s- on %s as per your request . Your avl Bal is %s .";
    private static final String HSBC_CARD = "HSBC:Thank you for using HSBC Debit Card XXXXX0789 for INR %s on %s at Test Store .Your available bal is INR %s .To report fraudulent transaction call 1800000000 (local) Or 1800000001 (overseas).";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountIdentifierRepository identifierRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AutoLinkCandidateLoader autoLinkLoader;
    @Autowired private SmsLoginLimiter limiter;

    private ApiTestClient api;
    private User user;
    private String token;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clearAll();
        String email = "sms-report-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        token = pair(user.getEmail());
        account = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Test Wallet",
                "financialPosition", "asset", "excludeFromNetAsset", false));
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

    private void summary(List<Map<String, Object>> lines) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/summary").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("lines", lines,
                                "skipped", Map.of("personal", 0, "otp", 0, "promo", 0, "unknownSender", 0)))))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    /** Turns both HSBC lines on for the generic account. */
    private void hsbcLinesOn() throws Exception {
        summary(List.of(line("HSBCIN", "789"), line("HSBCIN", "0789")));
        for (JsonNode l : api.getJson("/api/v1/sms/lines", 200).get("unassigned")) {
            api.postJson("/api/v1/sms/lines/" + l.get("id").asText() + "/on",
                    Map.of("accountId", account.toString(), "syncFrom", "2026-01-01"), 200);
        }
    }

    private Map<String, Object> msg(String body, String receivedAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("sender", "HSBCIN");
        m.put("body", body);
        m.put("receivedAt", receivedAt);
        m.put("simSlot", 1);
        Instant at = Instant.parse(receivedAt);
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
        return jdbc.queryForMap("SELECT outcome, read_status, transaction_id, id FROM sms_messages WHERE fingerprint = ?", message.get("fingerprint"));
    }

    private long transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ?", Long.class, user.getId().toString());
    }

    private Transaction txn(String amount, TransactionSource source, LocalDate date) {
        Account a = accountRepository.findById(account).orElseThrow();
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(a);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        t.setSource(source);
        t.setCategoryKey("food");
        t.setReviewType(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t);
    }

    private List<String> alertKinds() throws Exception {
        JsonNode accounts = api.getJson("/api/v1/sms/report", 200).get("accounts");
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode a : accounts) {
            if (a.get("accountId").asText().equals(account.toString())) {
                a.get("alerts").forEach(x -> out.add(x.get("kind").asText()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- posting off

    @Test
    void withPostingOffIntakeConfirmAndReplayWriteNoTransactionAndTouchNoRow() throws Exception {
        hsbcLinesOn();
        Transaction manual = txn("90.00", TransactionSource.manual, LocalDate.of(2026, 11, 9));
        Instant manualUpdated = jdbcInstant(manual.getId());
        long before = transactionCount();

        Map<String, Object> create = msg(HSBC.formatted("250.00", "08NOV", "1,000.00"), "2026-11-08T05:00:00Z");
        Map<String, Object> attach = msg(HSBC.formatted("90.00", "09NOV", "910.00"), "2026-11-09T05:00:00Z");
        Map<String, Object> needs = msg("A/c 123-456***-789 Rs 99.00 spent at Test Mart", "2026-11-10T06:00:00Z");
        send(List.of(create, attach, needs));
        assertThat(row(create).get("outcome")).isEqualTo("SHADOW_CREATE");
        assertThat(row(attach).get("outcome")).isEqualTo("SHADOW_ATTACH");
        assertThat(transactionCount()).isEqualTo(before);

        JsonNode confirmed = api.postJson("/api/v1/sms/messages/" + row(needs).get("id") + "/confirm", Map.of(), 200);
        assertThat(confirmed.get("outcome").asText()).isEqualTo("SHADOW_CREATE");
        assertThat(transactionCount()).isEqualTo(before);

        JsonNode replay = api.postJson("/api/v1/sms/replay", null, 200);
        assertThat(replay.get("replayed").asInt()).isEqualTo(3);
        assertThat(replay.get("outcomes").get("SHADOW_CREATE").asInt()).isEqualTo(2);
        assertThat(replay.get("outcomes").get("SHADOW_ATTACH").asInt()).isEqualTo(1);
        assertThat(replay.get("outcomes").get("CREATED").asInt()).isZero();
        assertThat(replay.get("outcomes").get("ATTACHED").asInt()).isZero();

        assertThat(transactionCount()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ? AND source = 'sms_alert'", Long.class, user.getId().toString())).isZero();
        assertThat(jdbcInstant(manual.getId())).isEqualTo(manualUpdated);
        assertThat(accountRepository.findById(account).orElseThrow().getBankDetails()).isNull();
    }

    private Instant jdbcInstant(UUID txnId) {
        return jdbc.queryForObject("SELECT updated_at FROM transactions WHERE id = ?", java.sql.Timestamp.class, txnId.toString()).toInstant();
    }

    @Test
    void replayEndpointOfAnotherUserChangesNothingOfMine() throws Exception {
        hsbcLinesOn();
        Map<String, Object> m = msg(HSBC.formatted("250.00", "08NOV", "1,000.00"), "2026-11-08T05:00:00Z");
        send(List.of(m));
        jdbc.update("UPDATE sms_messages SET outcome = NULL WHERE user_id = ?", user.getId().toString());

        String otherEmail = "sms-report-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        User otherUser = userRepository.findByEmail(otherEmail).orElseThrow();
        try {
            JsonNode res = other.postJson("/api/v1/sms/replay", null, 200);
            assertThat(res.get("replayed").asInt()).isZero();
            assertThat(row(m).get("outcome")).isNull();
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherUser.getId()));
        }
    }

    // ---------------------------------------------------------------- BALANCE_GAP across the two HSBC forms

    @Test
    void theTwoHsbcFormsOfOneAccountAreOneBalanceChain() throws Exception {
        hsbcLinesOn();
        // 3-digit form 1000 -> 900, 4-digit form (a debit card spend) 900 -> 850 -> 3-digit form 850 -> 800: no gap
        send(List.of(
                msg(HSBC.formatted("100.00", "05NOV", "900.00"), "2026-11-05T05:00:00Z"),
                msg(HSBC_CARD.formatted("50.00", "06NOV", "850.00"), "2026-11-06T05:00:00Z"),
                msg(HSBC.formatted("50.00", "07NOV", "800.00"), "2026-11-07T05:00:00Z")));
        assertThat(alertKinds()).doesNotContain("BALANCE_GAP");
    }

    @Test
    void aRealGapAcrossTheTwoHsbcFormsIsStillReported() throws Exception {
        hsbcLinesOn();
        send(List.of(
                msg(HSBC.formatted("100.00", "05NOV", "900.00"), "2026-11-05T05:00:00Z"),
                msg(HSBC_CARD.formatted("50.00", "06NOV", "700.00"), "2026-11-06T05:00:00Z")));
        assertThat(alertKinds()).contains("BALANCE_GAP");
    }

    @Test
    void aMessageWithoutABalanceInsideTheChainStillAddsToTheRunningAmount() throws Exception {
        hsbcLinesOn();
        String upi = "INR 30.00 is paid from HSBC account XXXXXX0789 to Test Payee on 06-Nov-26 with ref 612345678944. If this is not done by you, call 1800000000 to report.";
        send(List.of(
                msg(HSBC.formatted("100.00", "05NOV", "900.00"), "2026-11-05T05:00:00Z"),
                msg(upi, "2026-11-06T04:00:00Z"),
                msg(HSBC_CARD.formatted("20.00", "06NOV", "850.00"), "2026-11-06T05:00:00Z")));
        assertThat(alertKinds()).doesNotContain("BALANCE_GAP");
    }

    // ---------------------------------------------------------------- SILENT_LINE

    private void ageMessages(int days) {
        jdbc.update("UPDATE sms_messages SET received_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(days))), user.getId().toString());
    }

    private LocalDate agedDate(int days) {
        return com.financeos.core.time.AppTime.nowInstant().minus(Duration.ofDays(days)).atZone(com.financeos.core.time.AppTime.zone()).toLocalDate();
    }

    private void oneHsbcMessageAged(int days) throws Exception {
        hsbcLinesOn();
        send(List.of(msg(HSBC.formatted("100.00", "05NOV", "900.00"), "2026-11-05T05:00:00Z")));
        ageMessages(days);
    }

    @Test
    void anOnLineWithNewerTransactionsFromAnotherSourceIsSilent() throws Exception {
        oneHsbcMessageAged(20);
        txn("40.00", TransactionSource.manual, com.financeos.core.time.AppTime.today().minusDays(5));
        assertThat(alertKinds()).contains("SILENT_LINE");
        JsonNode alerts = api.getJson("/api/v1/sms/report", 200).get("accounts").get(0).get("alerts");
        int found = 0;
        for (JsonNode a : alerts) {
            if (a.get("kind").asText().equals("SILENT_LINE")) {
                found++;
                assertThat(a.get("message").asText()).isEqualTo("HSBC has sent nothing since "
                        + agedDate(20).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        + ", but this account has newer transactions");
                assertThat(a.get("date").asText()).isEqualTo(agedDate(20).toString());
            }
        }
        assertThat(found).isEqualTo(1);
    }

    @Test
    void anOldLineWithNothingNewerOnTheAccountIsNotSilent() throws Exception {
        oneHsbcMessageAged(20);
        assertThat(alertKinds()).doesNotContain("SILENT_LINE");
        // a text-message transaction is the feed itself, not another source
        txn("40.00", TransactionSource.sms_alert, com.financeos.core.time.AppTime.today().minusDays(5));
        assertThat(alertKinds()).doesNotContain("SILENT_LINE");
    }

    @Test
    void theOtherTransactionMustBeMoreThanThreeDaysAfterTheLastMessage() throws Exception {
        oneHsbcMessageAged(20);
        txn("40.00", TransactionSource.manual, agedDate(20).plusDays(3));
        assertThat(alertKinds()).doesNotContain("SILENT_LINE");
        txn("41.00", TransactionSource.manual, agedDate(20).plusDays(4));
        assertThat(alertKinds()).contains("SILENT_LINE");
    }

    @Test
    void onlyATransactionWithinTheLastSixtyDaysRaisesIt() throws Exception {
        oneHsbcMessageAged(100);
        txn("40.00", TransactionSource.manual, com.financeos.core.time.AppTime.today().minusDays(61));
        assertThat(alertKinds()).doesNotContain("SILENT_LINE");
        txn("41.00", TransactionSource.manual, com.financeos.core.time.AppTime.today().minusDays(59));
        assertThat(alertKinds()).containsOnlyOnce("SILENT_LINE");
    }

    @Test
    void twoLinesOfOneProviderOnOneAccountRaiseOneAlert() throws Exception {
        hsbcLinesOn();
        String upi = "INR 30.00 is paid from HSBC account XXXXXX0789 to Test Payee on 06-Nov-26 with ref 612345678955. If this is not done by you, call 1800000000 to report.";
        send(List.of(msg(HSBC.formatted("100.00", "05NOV", "900.00"), "2026-11-05T05:00:00Z"), msg(upi, "2026-11-06T04:00:00Z")));
        ageMessages(20);
        txn("40.00", TransactionSource.manual, com.financeos.core.time.AppTime.today().minusDays(2));
        assertThat(alertKinds()).containsOnlyOnce("SILENT_LINE");
    }

    @Test
    void aRecentMessageMeansNoSilentLine() throws Exception {
        hsbcLinesOn();
        send(List.of(msg(HSBC.formatted("100.00", "05NOV", "900.00"), "2026-11-05T05:00:00Z")));
        ageMessages(10);
        assertThat(alertKinds()).doesNotContain("SILENT_LINE");
    }

    @Test
    void aLineThatIsOffIsNeverSilent() throws Exception {
        hsbcLinesOn();
        send(List.of(msg(HSBC.formatted("100.00", "05NOV", "900.00"), "2026-11-05T05:00:00Z")));
        ageMessages(20);
        txn("40.00", TransactionSource.manual, com.financeos.core.time.AppTime.today().minusDays(5));
        for (JsonNode a : api.getJson("/api/v1/sms/lines", 200).get("accounts")) {
            for (JsonNode l : a.get("lines")) {
                api.postJson("/api/v1/sms/lines/" + l.get("id").asText() + "/off", null, 200);
            }
        }
        assertThat(alertKinds()).doesNotContain("SILENT_LINE");
    }

    // ---------------------------------------------------------------- suggestion for the two HSBC forms

    private UUID bankAccount(String name, String number) throws Exception {
        UUID id = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name,
                "financialPosition", "asset", "excludeFromNetAsset", false));
        Account a = accountRepository.findById(id).orElseThrow();
        a.setBank("hsbc");
        accountRepository.save(a);
        identifierRepository.save(new AccountIdentifier(UUID.randomUUID(), user, a, number, AccountIdentifierKind.ACCOUNT_NUMBER, Instant.now()));
        return id;
    }

    private String suggested(String ending) throws Exception {
        JsonNode view = api.getJson("/api/v1/sms/lines", 200);
        List<JsonNode> all = new java.util.ArrayList<>();
        view.get("unassigned").forEach(all::add);
        view.get("accounts").forEach(a -> a.get("lines").forEach(all::add));
        for (JsonNode l : all) {
            if (l.get("sender").asText().equals("HSBCIN") && l.get("ending").asText().equals(ending)) {
                return l.get("suggestedAccountId").isNull() ? null : l.get("suggestedAccountId").asText();
            }
        }
        return "missing";
    }

    @Test
    void bothHsbcFormsAreSuggestedToTheSameAccount() throws Exception {
        UUID hsbc = bankAccount("Test Savings", "770400789");
        summary(List.of(line("HSBCIN", "789"), line("HSBCIN", "0789")));
        assertThat(suggested("789")).isEqualTo(hsbc.toString());
        assertThat(suggested("0789")).isEqualTo(hsbc.toString());
    }

    @Test
    void anAmbiguousThreeDigitSuffixSuggestsNothingWhileTheFourDigitEndingStillMatchesOne() throws Exception {
        UUID first = bankAccount("Test Savings One", "770400789");
        bankAccount("Test Savings Two", "880401789");
        summary(List.of(line("HSBCIN", "789"), line("HSBCIN", "0789")));
        assertThat(suggested("789")).isNull();
        assertThat(suggested("0789")).isEqualTo(first.toString());
    }

    // ---------------------------------------------------------------- auto-linking and sms_alert

    @Test
    void autoLinkingSeesAnSmsAlertRowOnAGenericAccountLikeAManualOne() {
        Transaction manual = txn("120.00", TransactionSource.manual, LocalDate.of(2026, 11, 8));
        Transaction sms = txn("130.00", TransactionSource.sms_alert, LocalDate.of(2026, 11, 8));
        List<UUID> ids = autoLinkLoader.load(user.getId()).stream().map(c -> c.id()).toList();
        assertThat(ids).contains(manual.getId(), sms.getId());
    }
}
