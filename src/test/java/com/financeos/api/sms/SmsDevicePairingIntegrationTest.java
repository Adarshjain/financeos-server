package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.sms.device.PairAttemptLimiter;
import com.financeos.sms.device.SmsTokens;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.time.Instant;
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

/** Pairing codes (expiry, single use) and the device token boundary: valid only on /api/v1/sms/device/**. */
@SpringBootTest
@AutoConfigureMockMvc
class SmsDevicePairingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private PairAttemptLimiter limiter;

    private ApiTestClient api;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clear();
        String email = "sms-pair-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        limiter.clear();
    }

    private String newCode() throws Exception {
        JsonNode body = api.postJson("/api/v1/sms/pairing-code", null, 200);
        assertThat(body.get("code").asText()).matches("[A-HJKMNP-Z2-9]{8}");
        assertThat(Instant.parse(body.get("expiresAt").asText())).isAfter(Instant.now().plusSeconds(9 * 60));
        return body.get("code").asText();
    }

    private MockHttpServletResponse pair(String code) throws Exception {
        return mockMvc.perform(post("/api/v1/sms/pair").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("code", code, "deviceName", "Test phone")))).andReturn().getResponse();
    }

    private String token() throws Exception {
        MockHttpServletResponse response = pair(newCode());
        assertThat(response.getStatus()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString()).get("token").asText();
    }

    @Test
    void codeIsStoredHashedAndPairsExactlyOnce() throws Exception {
        String code = newCode();
        assertThat(jdbc.queryForList("SELECT code_hash FROM sms_pairing_codes WHERE user_id = ?", String.class, user.getId().toString()))
                .containsExactly(SmsTokens.sha256(code));

        MockHttpServletResponse first = pair(code.toLowerCase());
        assertThat(first.getStatus()).isEqualTo(200);
        String token = mapper.readTree(first.getContentAsString()).get("token").asText();
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForList("SELECT token_hash FROM sms_devices WHERE user_id = ?", String.class, user.getId().toString()))
                .containsExactly(SmsTokens.sha256(token));

        assertThat(pair(code).getStatus()).isEqualTo(400);
    }

    @Test
    void expiredAndUnknownCodesAreRefusedTheSameWay() throws Exception {
        String code = newCode();
        jdbc.update("UPDATE sms_pairing_codes SET expires_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(5)), user.getId().toString());
        MockHttpServletResponse expired = pair(code);
        MockHttpServletResponse unknown = pair("ABCDEFGH");
        assertThat(expired.getStatus()).isEqualTo(400);
        assertThat(unknown.getStatus()).isEqualTo(400);
        assertThat(mapper.readTree(expired.getContentAsString()).get("message"))
                .isEqualTo(mapper.readTree(unknown.getContentAsString()).get("message"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_devices WHERE user_id = ?", Integer.class, user.getId().toString())).isZero();
    }

    @Test
    void repeatedWrongCodesAreThrottled() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(pair("ZZZZZZZZ").getStatus()).isEqualTo(400);
        }
        assertThat(pair(newCode()).getStatus()).isEqualTo(429);
    }

    @Test
    void deviceTokenWorksOnDeviceEndpointsAndTouchesLastSeen() throws Exception {
        String token = token();
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.catalogue.senders").isArray());
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM sms_devices WHERE user_id = ?", java.sql.Timestamp.class,
                user.getId().toString())).isNotNull();
    }

    @Test
    void deviceTokenAuthenticatesNothingElse() throws Exception {
        String token = token();
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sms/lines").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/sms/pairing-code").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousSessionAndBadTokensAreRejectedOnDeviceEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/sms/device/config")).andExpect(status().isUnauthorized());
        assertThat(api.get("/api/v1/sms/device/config").getStatus()).isEqualTo(401);
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Basic abc")).andExpect(status().isUnauthorized());
    }

    @Test
    void revokedDeviceTokenIsRejected() throws Exception {
        String token = token();
        UUID deviceId = UUID.fromString(api.getJson("/api/v1/sms/devices", 200).get(0).get("id").asText());
        api.postJson("/api/v1/sms/devices/" + deviceId + "/revoke", null, 204);
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pauseResumeShowOnTheDeviceConfigAndAnotherUsersDeviceIsNotFound() throws Exception {
        String token = token();
        UUID deviceId = UUID.fromString(api.getJson("/api/v1/sms/devices", 200).get(0).get("id").asText());
        api.postJson("/api/v1/sms/devices/" + deviceId + "/pause", null, 204);
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.paused").value(true));
        api.postJson("/api/v1/sms/devices/" + deviceId + "/resume", null, 204);
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.paused").value(false));

        String otherEmail = "sms-pair-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        UUID otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        try {
            other.postJson("/api/v1/sms/devices/" + deviceId + "/pause", null, 404);
            other.postJson("/api/v1/sms/devices/" + deviceId + "/revoke", null, 404);
            assertThat(other.getJson("/api/v1/sms/devices", 200)).isEmpty();
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherId));
        }
    }
}
