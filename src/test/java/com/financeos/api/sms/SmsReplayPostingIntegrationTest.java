package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

/** A posting-on replay over more than one chunk gives the outcomes and transactions intake gave. Invented data. */
@SpringBootTest(properties = "financeos.sms.posting-enabled=true")
@AutoConfigureMockMvc
class SmsReplayPostingIntegrationTest {

    private static final String HSBC = "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been debited with INR %s- on 08NOV as per your request . Your avl Bal is 9,999.00 .";
    private static final int CREATE = 150;
    private static final int ATTACH_TEMPLATE = 60;
    private static final int ATTACH_GENERIC = 60;
    private static final int NO_MATCH_GENERIC = 30;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private SmsPipelineService pipeline;
    @Autowired private PairAttemptLimiter limiter;

    private ApiTestClient api;
    private User user;
    private String token;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clear();
        String email = "sms-replay-post-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        String code = api.postJson("/api/v1/sms/pairing-code", null, 200).get("code").asText();
        token = mapper.readTree(mockMvc.perform(post("/api/v1/sms/pair").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("code", code, "deviceName", "Test phone"))))
                .andReturn().getResponse().getContentAsString()).get("token").asText();
        account = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Test Wallet",
                "financialPosition", "asset", "excludeFromNetAsset", false));
        Map<String, Object> l = new HashMap<>();
        l.put("sender", "HSBCIN");
        l.put("ending", "789");
        l.put("family", null);
        l.put("msgCount", 5);
        l.put("txnCount", 4);
        l.put("firstMsgAt", "2026-01-05T04:00:00Z");
        l.put("lastMsgAt", "2026-11-01T04:00:00Z");
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/summary").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("lines", List.of(l),
                        "skipped", Map.of("personal", 0, "otp", 0, "promo", 0, "unknownSender", 0))))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        for (JsonNode n : api.getJson("/api/v1/sms/lines", 200).get("unassigned")) {
            api.postJson("/api/v1/sms/lines/" + n.get("id").asText() + "/on",
                    Map.of("accountId", account.toString(), "syncFrom", "2026-11-01"), 200);
        }
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        limiter.clear();
    }

    private void manual(String amount) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(accountRepository.findById(account).orElseThrow());
        t.setDate(LocalDate.of(2026, 11, 8));
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setCategoryKey("food");
        t.setReviewType(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        transactionRepository.save(t);
    }

    private Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT fingerprint, read_status, outcome, transaction_id FROM sms_messages WHERE user_id = ? ORDER BY fingerprint",
                rs -> {
                    out.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4));
                }, user.getId().toString());
        return out;
    }

    private long transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ?", Long.class, user.getId().toString());
    }

    @Test
    void aPostingReplayOverSeveralChunksChangesNoOutcomeAndCreatesNothingTwice() throws Exception {
        Instant base = Instant.parse("2026-11-08T05:00:00Z");
        List<Map<String, Object>> all = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < CREATE + ATTACH_TEMPLATE + ATTACH_GENERIC + NO_MATCH_GENERIC; i++) {
            String amount = (100 + i) + ".00";
            String body;
            if (i < CREATE) {
                body = HSBC.formatted(amount);
            } else if (i < CREATE + ATTACH_TEMPLATE) {
                manual(amount);
                body = HSBC.formatted(amount);
            } else if (i < CREATE + ATTACH_TEMPLATE + ATTACH_GENERIC) {
                manual(amount);
                body = "A/c 123-456***-789 Rs " + amount + " spent at Test Mart " + i;
            } else {
                body = "A/c 123-456***-789 Rs " + amount + " spent at Test Mart " + i;
            }
            // interleave the kinds so every chunk holds a mix
            Instant at = base.plusSeconds((long) ((i * 7) % 270) * 60 + i / 270);
            Map<String, Object> m = new HashMap<>();
            m.put("sender", "HSBCIN");
            m.put("body", body);
            m.put("receivedAt", at.toString());
            m.put("simSlot", 1);
            m.put("fingerprint", SmsTokens.sha256("HSBCIN\n" + at.toEpochMilli() + "\n" + body));
            all.add(m);
        }
        for (int from = 0; from < all.size(); from += 100) {
            MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/messages").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(Map.of("messages", all.subList(from, Math.min(all.size(), from + 100)))))).andReturn().getResponse();
            assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        }
        Map<String, String> before = snapshot();
        long txnBefore = transactionCount();
        assertThat(before.values().stream().filter(v -> v.contains("|CREATED|")).count()).isEqualTo(CREATE);
        assertThat(before.values().stream().filter(v -> v.contains("|ATTACHED|")).count()).isEqualTo(ATTACH_TEMPLATE + ATTACH_GENERIC);
        assertThat(before.values().stream().filter(v -> v.startsWith("NEEDS_CONFIRM|null|")).count()).isEqualTo(NO_MATCH_GENERIC);
        assertThat(txnBefore).isEqualTo(CREATE + ATTACH_TEMPLATE + ATTACH_GENERIC);

        // posted messages are untouched by a replay
        assertThat(pipeline.replay(user.getId())).isEqualTo(NO_MATCH_GENERIC);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(transactionCount()).isEqualTo(txnBefore);

        // a message that lost its outcome (four of five) is decided again to the same outcome and transaction
        int[] k = {0};
        List<String> fingerprints = new ArrayList<>(before.keySet());
        for (String fp : fingerprints) {
            if (k[0]++ % 5 != 0 && !before.get(fp).startsWith("NEEDS_CONFIRM")) {
                jdbc.update("UPDATE sms_messages SET outcome = NULL, transaction_id = NULL WHERE fingerprint = ? AND user_id = ?", fp, user.getId().toString());
            }
        }
        assertThat(pipeline.replay(user.getId())).isGreaterThan(200);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(transactionCount()).isEqualTo(txnBefore);
        assertThat(before.values().stream().map(v -> v.split("\\|")[2]).filter(v -> !v.equals("null")).distinct().count())
                .isEqualTo(CREATE + ATTACH_TEMPLATE + ATTACH_GENERIC);
    }
}
