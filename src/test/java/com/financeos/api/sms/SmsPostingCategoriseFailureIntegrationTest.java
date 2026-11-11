package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.categorization.CategoryAssigner;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.sms.device.SmsLoginLimiter;
import com.financeos.sms.device.SmsTokens;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.time.Instant;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Categorising a posted message fails: the transaction is still created and the message is CREATED. Invented data. */
@SpringBootTest(properties = "financeos.sms.posting-enabled=true")
@AutoConfigureMockMvc
class SmsPostingCategoriseFailureIntegrationTest {

    private static final String HSBC = "HSBC: Dear Customer, your HSBC A/c 123-456***-789 has been debited with INR 250.00- on 08NOV as per your request . Your avl Bal is 1,000.00 .";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private SmsLoginLimiter limiter;
    @MockBean private CategoryAssigner categoryAssigner;

    private ApiTestClient api;
    private User user;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clearAll();
        String email = "sms-catfail-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        token = SmsTestLogin.token(mockMvc, mapper, email);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        limiter.clearAll();
    }

    @Test
    void aCategorisationFailureLeavesTheTransactionCreatedAndTheMessageCreated() throws Exception {
        when(categoryAssigner.assignOnArrivalById(any(), any(), any())).thenThrow(new IllegalStateException("categoriser down"));
        UUID account = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Test Wallet",
                "financialPosition", "asset", "excludeFromNetAsset", false));
        Map<String, Object> l = new HashMap<>();
        l.put("sender", "HSBCIN");
        l.put("ending", "789");
        l.put("family", null);
        l.put("msgCount", 5);
        l.put("txnCount", 4);
        l.put("firstMsgAt", "2026-01-05T04:00:00Z");
        l.put("lastMsgAt", "2026-11-01T04:00:00Z");
        mockMvc.perform(post("/api/v1/sms/device/summary").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("lines", List.of(l),
                        "skipped", Map.of("personal", 0, "otp", 0, "promo", 0, "unknownSender", 0))))).andReturn();
        JsonNode line = api.getJson("/api/v1/sms/lines", 200).get("unassigned").get(0);
        api.postJson("/api/v1/sms/lines/" + line.get("id").asText() + "/on",
                Map.of("accountId", account.toString(), "syncFrom", "2026-11-01"), 200);

        String receivedAt = "2026-11-08T05:00:00Z";
        String fingerprint = SmsTokens.sha256("HSBCIN\n" + Instant.parse(receivedAt).toEpochMilli() + "\n" + HSBC);
        Map<String, Object> m = new HashMap<>();
        m.put("sender", "HSBCIN");
        m.put("body", HSBC);
        m.put("receivedAt", receivedAt);
        m.put("simSlot", 1);
        m.put("fingerprint", fingerprint);
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/sms/device/messages").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("messages", List.of(m)))))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(res.getContentAsString()).get("results").get(0).get("status").asText()).isEqualTo("ACCEPTED");

        Map<String, Object> row = jdbc.queryForMap("SELECT outcome, transaction_id FROM sms_messages WHERE fingerprint = ?", fingerprint);
        assertThat(row.get("outcome")).isEqualTo("CREATED");
        assertThat(row.get("transaction_id")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE user_id = ? AND source = 'sms_alert'", Long.class,
                user.getId().toString())).isEqualTo(1);
        verify(categoryAssigner, atLeastOnce()).assignOnArrivalById(any(), any(), any());
    }
}
