package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
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
import java.time.Duration;
import java.time.Instant;
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

/** A replay over about 1,500 generic readings of three accounts gives the outcomes intake gave, in bounded time. Invented data. */
@SpringBootTest
@AutoConfigureMockMvc
class SmsReplayVolumeIntegrationTest {

    private static final int MESSAGES = 1500;
    private static final String[] ENDINGS = {"111", "222", "333"};

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
    private final List<UUID> accounts = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        limiter.clearAll();
        String email = "sms-volume-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        token = SmsTestLogin.token(mockMvc, mapper, email);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String e : ENDINGS) {
            accounts.add(api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Test Wallet " + e,
                    "financialPosition", "asset", "excludeFromNetAsset", false)));
            Map<String, Object> l = new HashMap<>();
            l.put("sender", "HSBCIN");
            l.put("ending", e);
            l.put("family", null);
            l.put("msgCount", 5);
            l.put("txnCount", 4);
            l.put("firstMsgAt", "2026-01-05T04:00:00Z");
            l.put("lastMsgAt", "2026-11-01T04:00:00Z");
            lines.add(l);
        }
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/summary").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("lines", lines,
                        "skipped", Map.of("personal", 0, "otp", 0, "promo", 0, "unknownSender", 0))))).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        for (JsonNode l : api.getJson("/api/v1/sms/lines", 200).get("unassigned")) {
            int idx = List.of(ENDINGS).indexOf(l.get("ending").asText());
            api.postJson("/api/v1/sms/lines/" + l.get("id").asText() + "/on",
                    Map.of("accountId", accounts.get(idx).toString(), "syncFrom", "2026-01-01"), 200);
        }
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        limiter.clearAll();
    }

    private Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("SELECT fingerprint, read_status, outcome, transaction_id, duplicate_of FROM sms_messages WHERE user_id = ? ORDER BY fingerprint",
                rs -> {
                    out.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getString(4) + "|" + rs.getString(5));
                }, user.getId().toString());
        return out;
    }

    @Test
    void aReplayOfAboutFifteenHundredGenericReadingsGivesTheOutcomesIntakeGave() throws Exception {
        Instant base = AppTime.nowInstant().minus(Duration.ofDays(40));
        List<Map<String, Object>> batch = new ArrayList<>();
        int matched = 0;
        for (int i = 0; i < MESSAGES; i++) {
            int a = i % 3;
            Instant at = base.plusSeconds(i * 1200L);
            String amount = (10 + i / 100) + "." + String.format("%02d", i % 100);
            String body = "A/c XX" + ENDINGS[a] + " Rs " + amount + " spent at Test Mart " + i;
            if (i % 3 == 0) {
                Transaction t = new Transaction();
                t.setUser(user);
                t.setAccount(accountRepository.findById(accounts.get(a)).orElseThrow());
                t.setDate(at.atZone(AppTime.zone()).toLocalDate());
                t.setAmount(new BigDecimal(amount));
                t.setType(TransactionType.DEBIT);
                t.setSource(TransactionSource.manual);
                t.setCategoryKey("food");
                t.setReviewType(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED);
                t.setTransactionUnderMonitoring(false);
                t.setTransactionExcluded(false);
                transactionRepository.save(t);
                matched++;
            }
            Map<String, Object> m = new HashMap<>();
            m.put("sender", "HSBCIN");
            m.put("body", body);
            m.put("receivedAt", at.toString());
            m.put("simSlot", 1);
            m.put("fingerprint", SmsTokens.sha256("HSBCIN\n" + at.toEpochMilli() + "\n" + body));
            batch.add(m);
            if (batch.size() == 200 || i == MESSAGES - 1) {
                MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/messages").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("messages", batch)))).andReturn().getResponse();
                assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
                batch.clear();
            }
        }
        Map<String, String> byIntake = snapshot();
        assertThat(byIntake).hasSize(MESSAGES);
        assertThat(byIntake.values().stream().filter(v -> v.startsWith("READ|SHADOW_ATTACH|")).count()).isEqualTo(matched);
        assertThat(byIntake.values().stream().filter(v -> v.startsWith("NEEDS_CONFIRM|null|")).count()).isEqualTo(MESSAGES - matched);

        long t0 = System.nanoTime();
        int run = pipeline.replay(user.getId());
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(run).isEqualTo(MESSAGES);
        assertThat(snapshot()).isEqualTo(byIntake);
        // generous bound only: the old shape took many times this on the same data
        assertThat(ms).isLessThan(120_000);
    }

    @Test
    void twoEqualPaymentsInDifferentChunksShareOneLedgerTransactionOnlyOnce() throws Exception {
        java.time.LocalDate day0 = AppTime.today().minusDays(12);
        Instant base = day0.atStartOfDay(AppTime.zone()).toInstant();
        List<Map<String, Object>> batch = new ArrayList<>();
        UUID sharedId = null;
        for (int i = 0; i < 410; i++) {
            Instant at = base.plusSeconds(i * 1200L);
            boolean twin = i == 199 || i == 201;
            String amount = twin ? "77.77" : (10 + i / 100) + "." + String.format("%02d", i % 100);
            if (i == 199) {
                Transaction t = new Transaction();
                t.setUser(user);
                t.setAccount(accountRepository.findById(accounts.get(0)).orElseThrow());
                t.setDate(at.atZone(AppTime.zone()).toLocalDate());
                t.setAmount(new BigDecimal(amount));
                t.setType(TransactionType.DEBIT);
                t.setSource(TransactionSource.manual);
                t.setCategoryKey("food");
                t.setReviewType(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED);
                t.setTransactionUnderMonitoring(false);
                t.setTransactionExcluded(false);
                sharedId = transactionRepository.save(t).getId();
            }
            String body = "A/c XX" + ENDINGS[0] + " Rs " + amount + " spent at Test Mart " + i;
            Map<String, Object> m = new HashMap<>();
            m.put("sender", "HSBCIN");
            m.put("body", body);
            m.put("receivedAt", at.toString());
            m.put("simSlot", 1);
            m.put("fingerprint", SmsTokens.sha256("HSBCIN\n" + at.toEpochMilli() + "\n" + body));
            batch.add(m);
            if (batch.size() == 200 || i == 409) {
                MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/messages").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("messages", batch)))).andReturn().getResponse();
                assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
                batch.clear();
            }
        }
        final UUID shared = sharedId;
        Map<String, String> byIntake = snapshot();
        long holders = byIntake.values().stream().filter(v -> v.startsWith("READ|SHADOW_ATTACH|" + shared + "|")).count();
        assertThat(holders).isEqualTo(1);

        assertThat(pipeline.replay(user.getId())).isEqualTo(410);
        Map<String, String> after = snapshot();
        assertThat(after).isEqualTo(byIntake);
        assertThat(after.values().stream().filter(v -> v.startsWith("READ|SHADOW_ATTACH|" + shared + "|")).count()).isEqualTo(1);
        // the later twin does not claim it
        assertThat(after.values().stream().filter(v -> v.contains(String.valueOf(shared))).count()).isEqualTo(1);
    }
}
