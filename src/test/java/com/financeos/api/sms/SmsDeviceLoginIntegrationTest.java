package com.financeos.api.sms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.sms.device.GoogleIdTokenCheck;
import com.financeos.sms.device.SmsDeviceLoginService;
import com.financeos.sms.device.SmsDeviceService;
import com.financeos.sms.device.SmsLoginLimiter;
import com.financeos.sms.device.SmsTokens;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
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

/** Device sign-in (email and password, Google ID token) and the device token boundary: valid only on /api/v1/sms/device/**. */
@SpringBootTest(properties = "google.oauth.client-id=sms-login-client.example.test")
@AutoConfigureMockMvc
class SmsDeviceLoginIntegrationTest {

    private static final String AUDIENCE = "sms-login-client.example.test";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private SmsLoginLimiter limiter;
    @Autowired private AuthService authService;
    @Autowired private SmsDeviceService deviceService;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @MockBean private GoogleIdTokenCheck google;

    private ApiTestClient api;
    private User user;
    private String email;

    @BeforeEach
    void setUp() throws Exception {
        limiter.clearAll();
        email = "sms-login-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
        limiter.clearAll();
    }

    private MockHttpServletResponse login(String mail, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/sms/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", mail, "password", password, "deviceName", "Test phone"))))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse googleLogin(String deviceName) throws Exception {
        return mockMvc.perform(post("/api/v1/sms/login/google").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("idToken", "stub-token", "deviceName", deviceName))))
                .andReturn().getResponse();
    }

    private String token() throws Exception {
        MockHttpServletResponse response = login(email, ApiTestClient.PASSWORD);
        assertThat(response.getStatus()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString()).get("token").asText();
    }

    private void stubGoogle(boolean valid, String subject, String mail, boolean verified) {
        when(google.check(any(), eq(AUDIENCE))).thenReturn(new GoogleIdTokenCheck.Result(valid, subject, mail, verified));
    }

    private int users() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
    }

    private int devices() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sms_devices WHERE user_id = ?", Integer.class, user.getId().toString());
    }

    private MockHttpServletResponse loginWithInstall(String mail, String installId) throws Exception {
        return mockMvc.perform(post("/api/v1/sms/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", mail, "password", ApiTestClient.PASSWORD,
                        "deviceName", "Test phone", "installId", installId)))).andReturn().getResponse();
    }

    private String installToken(String installId) throws Exception {
        MockHttpServletResponse response = loginWithInstall(email, installId);
        assertThat(response.getStatus()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString()).get("token").asText();
    }

    private int deviceStatus(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void sameInstallIdByPasswordTwiceReusesTheRowAndRefusesTheOldToken() throws Exception {
        String first = installToken("install-aaaa-1111");
        String second = installToken("install-aaaa-1111");
        assertThat(devices()).isEqualTo(1);
        assertThat(deviceStatus(first)).isEqualTo(401);
        assertThat(deviceStatus(second)).isEqualTo(200);
        assertThat(api.getJson("/api/v1/sms/devices", 200)).hasSize(1);
    }

    @Test
    void sameInstallIdByPasswordAndByGoogleIsOneRow() throws Exception {
        installToken("install-bbbb-2222");
        stubGoogle(true, "sub-" + UUID.randomUUID(), email, true);
        MockHttpServletResponse response = mockMvc.perform(post("/api/v1/sms/login/google").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("idToken", "stub-token", "deviceName", "Test phone",
                        "installId", "install-bbbb-2222")))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(devices()).isEqualTo(1);
    }

    @Test
    void differentInstallIdsAreTwoDevicesAndBothTokensWork() throws Exception {
        String a = installToken("install-cccc-3333");
        String b = installToken("install-dddd-4444");
        assertThat(devices()).isEqualTo(2);
        assertThat(deviceStatus(a)).isEqualTo(200);
        assertThat(deviceStatus(b)).isEqualTo(200);
    }

    @Test
    void noInstallIdTwiceIsTwoRows() throws Exception {
        token();
        token();
        assertThat(devices()).isEqualTo(2);
    }

    @Test
    void aRevokedDeviceSigningInAgainWithItsInstallIdIsConnectedAgainOnTheSameRow() throws Exception {
        String first = installToken("install-eeee-5555");
        UUID deviceId = UUID.fromString(api.getJson("/api/v1/sms/devices", 200).get(0).get("id").asText());
        api.postJson("/api/v1/sms/devices/" + deviceId + "/revoke", null, 204);
        assertThat(deviceStatus(first)).isEqualTo(401);
        String second = installToken("install-eeee-5555");
        assertThat(devices()).isEqualTo(1);
        JsonNode row = api.getJson("/api/v1/sms/devices", 200).get(0);
        assertThat(row.get("id").asText()).isEqualTo(deviceId.toString());
        assertThat(row.get("revokedAt").isNull()).isTrue();
        assertThat(deviceStatus(second)).isEqualTo(200);
    }

    @Test
    void olderRowsWithTheSameInstallIdAreRevokedWhenThePhoneSignsInAgain() throws Exception {
        String first = installToken("install-kkkk-9090");
        String second = token();
        // two connected rows for one install can only come from two sign-ins at the same moment
        jdbc.update("UPDATE sms_devices SET install_id = ? WHERE user_id = ? AND install_id IS NULL", "install-kkkk-9090",
                user.getId().toString());
        String third = installToken("install-kkkk-9090");
        assertThat(devices()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_devices WHERE user_id = ? AND revoked_at IS NULL", Integer.class,
                user.getId().toString())).isEqualTo(1);
        assertThat(deviceStatus(first)).isEqualTo(401);
        assertThat(deviceStatus(second)).isEqualTo(401);
        assertThat(deviceStatus(third)).isEqualTo(200);
    }

    @Test
    void theSameInstallIdUnderAnotherUserIsAnotherPhone() throws Exception {
        String first = installToken("install-ffff-6666");
        String otherEmail = "sms-login-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        UUID otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        try {
            assertThat(loginWithInstall(otherEmail, "install-ffff-6666").getStatus()).isEqualTo(200);
            assertThat(devices()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sms_devices WHERE user_id = ?", Integer.class, otherId.toString()))
                    .isEqualTo(1);
            assertThat(deviceStatus(first)).isEqualTo(200);
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherId));
        }
    }

    @Test
    void anInvalidInstallIdIs400AndCreatesNoRow() throws Exception {
        assertThat(loginWithInstall(email, "short").getStatus()).isEqualTo(400);
        assertThat(loginWithInstall(email, "bad char id !!").getStatus()).isEqualTo(400);
        assertThat(loginWithInstall(email, "x".repeat(65)).getStatus()).isEqualTo(400);
        assertThat(devices()).isZero();
    }

    @Test
    void pausedStaysAcrossASignInWithTheSameInstallId() throws Exception {
        installToken("install-gggg-7777");
        UUID deviceId = UUID.fromString(api.getJson("/api/v1/sms/devices", 200).get(0).get("id").asText());
        api.postJson("/api/v1/sms/devices/" + deviceId + "/pause", null, 204);
        String second = installToken("install-gggg-7777");
        assertThat(devices()).isEqualTo(1);
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + second))
                .andExpect(jsonPath("$.paused").value(true));
    }

    @Test
    void passwordLoginGivesAHashedTokenThatWorksOnDeviceEndpointsAndTouchesLastSeen() throws Exception {
        String token = token();
        assertThat(token).hasSize(43);
        assertThat(jdbc.queryForList("SELECT token_hash FROM sms_devices WHERE user_id = ?", String.class, user.getId().toString()))
                .containsExactly(SmsTokens.sha256(token));
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(false))
                .andExpect(jsonPath("$.catalogue.senders").isArray());
        assertThat(jdbc.queryForObject("SELECT last_seen_at FROM sms_devices WHERE user_id = ?", java.sql.Timestamp.class,
                user.getId().toString())).isNotNull();
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSame401() throws Exception {
        MockHttpServletResponse wrong = login(email, "not-the-password");
        MockHttpServletResponse unknown = login("nobody-" + UUID.randomUUID() + "@example.test", "whatever-pass");
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(unknown.getStatus()).isEqualTo(401);
        JsonNode a = mapper.readTree(wrong.getContentAsString());
        JsonNode b = mapper.readTree(unknown.getContentAsString());
        assertThat(a.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(a.get("message")).isEqualTo(b.get("message"));
        assertThat(a.get("code")).isEqualTo(b.get("code"));
        assertThat(devices()).isZero();
    }

    @Test
    void repeatedFailuresLockOneEmailOutWhileAnotherStillWorks() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertThat(login(email, "bad-" + i).getStatus()).isEqualTo(401);
        }
        MockHttpServletResponse locked = login(email.toUpperCase(), ApiTestClient.PASSWORD);
        assertThat(locked.getStatus()).isEqualTo(429);
        assertThat(mapper.readTree(locked.getContentAsString()).get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(devices()).isZero();

        String otherEmail = "sms-login-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        UUID otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        try {
            assertThat(login(otherEmail, ApiTestClient.PASSWORD).getStatus()).isEqualTo(200);
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherId));
        }
    }

    @Test
    void aCorrectLoginClearsEarlierFailures() throws Exception {
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 7; i++) {
                assertThat(login(email, "bad-" + i).getStatus()).isEqualTo(401);
            }
            assertThat(login(email, ApiTestClient.PASSWORD).getStatus()).isEqualTo(200);
        }
        assertThat(devices()).isEqualTo(2);
    }

    @Test
    void passwordLoginDoesNotCreateAWebSessionAndValidatesTheBody() throws Exception {
        MockHttpServletResponse ok = login(email, ApiTestClient.PASSWORD);
        assertThat(ok.getCookies()).isEmpty();
        assertThat(ok.getHeader("Set-Cookie")).isNull();
        for (Map<String, String> body : List.of(
                Map.of("email", email, "password", ApiTestClient.PASSWORD),
                Map.of("email", email, "password", ApiTestClient.PASSWORD, "deviceName", "  "),
                Map.of("email", email, "password", ApiTestClient.PASSWORD, "deviceName", "x".repeat(81)),
                Map.of("email", email, "deviceName", "Phone"))) {
            mockMvc.perform(post("/api/v1/sms/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/sms/login/google").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("idToken", "t", "deviceName", "x".repeat(81))))).andExpect(status().isBadRequest());
        assertThat(devices()).isEqualTo(1);
    }

    @Test
    void googleLoginMatchedByGoogleIdGivesAToken() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        jdbc.update("UPDATE users SET google_id = ? WHERE id = ?", sub, user.getId().toString());
        stubGoogle(true, sub, "different-" + UUID.randomUUID() + "@example.test", true);
        MockHttpServletResponse response = googleLogin("Test phone");
        assertThat(response.getStatus()).isEqualTo(200);
        String token = mapper.readTree(response.getContentAsString()).get("token").asText();
        mockMvc.perform(get("/api/v1/sms/device/config").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(devices()).isEqualTo(1);
    }

    @Test
    void googleLoginMatchedByEmailGivesATokenAndLeavesTheUserRowUnchanged() throws Exception {
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM users WHERE id = ?", user.getId().toString());
        stubGoogle(true, "sub-" + UUID.randomUUID(), email, true);
        MockHttpServletResponse response = googleLogin("Test phone");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(mapper.readTree(response.getContentAsString()).get("token").asText()).hasSize(43);
        assertThat(jdbc.queryForList("SELECT * FROM users WHERE id = ?", user.getId().toString())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT google_id FROM users WHERE id = ?", String.class, user.getId().toString())).isNull();
    }

    @Test
    void googleLoginDoesNotMatchByEmailAnAccountTiedToAnotherGoogleAccount() throws Exception {
        jdbc.update("UPDATE users SET google_id = ? WHERE id = ?", "sub-owner-" + UUID.randomUUID(), user.getId().toString());
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM users WHERE id = ?", user.getId().toString());
        stubGoogle(true, "sub-intruder-" + UUID.randomUUID(), email, true);
        MockHttpServletResponse response = googleLogin("Test phone");
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForList("SELECT * FROM users WHERE id = ?", user.getId().toString())).isEqualTo(before);
        assertThat(devices()).isZero();
    }

    @Test
    void aNoUserAnswerIsNotCountedAgainstTheSubject() throws Exception {
        stubGoogle(true, "sub-fixed-1", "ghost-" + UUID.randomUUID() + "@example.test", true);
        for (int i = 0; i < 12; i++) {
            assertThat(googleLogin("Test phone").getStatus()).isEqualTo(404);
        }
    }

    @Test
    void aLongCorrectPasswordSignsIn() throws Exception {
        String longPassword = "p".repeat(300);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(longPassword), user.getId().toString());
        assertThat(login(email, longPassword).getStatus()).isEqualTo(200);
        assertThat(login(email, "q" + "p".repeat(299)).getStatus()).isEqualTo(401);
    }

    @Test
    void googleLoginForAnUnknownUserIs404AndCreatesNobody() throws Exception {
        int usersBefore = users();
        String ghost = "ghost-" + UUID.randomUUID() + "@example.test";
        stubGoogle(true, "sub-" + UUID.randomUUID(), ghost, true);
        MockHttpServletResponse response = googleLogin("Test phone");
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(users()).isEqualTo(usersBefore);
        assertThat(userRepository.findByEmail(ghost)).isEmpty();
    }

    @Test
    void googleLoginWithUnverifiedEmailOrBadTokenIs401() throws Exception {
        stubGoogle(true, "sub-" + UUID.randomUUID(), email, false);
        MockHttpServletResponse unverified = googleLogin("Test phone");
        assertThat(unverified.getStatus()).isEqualTo(401);
        assertThat(mapper.readTree(unverified.getContentAsString()).get("code").asText()).isEqualTo("UNAUTHORIZED");

        stubGoogle(false, "sub-" + UUID.randomUUID(), email, true);
        assertThat(googleLogin("Test phone").getStatus()).isEqualTo(401);
        when(google.check(any(), eq(AUDIENCE))).thenReturn(GoogleIdTokenCheck.Result.unreadable());
        assertThat(googleLogin("Test phone").getStatus()).isEqualTo(401);
        assertThat(devices()).isZero();
    }

    @Test
    void googleLoginIsThrottledPerSubjectAndUnreadableTokensShareASmallBucket() throws Exception {
        String sub = "sub-" + UUID.randomUUID();
        stubGoogle(false, sub, email, true);
        for (int i = 0; i < 8; i++) {
            assertThat(googleLogin("Test phone").getStatus()).isEqualTo(401);
        }
        assertThat(googleLogin("Test phone").getStatus()).isEqualTo(429);
        stubGoogle(true, "sub-other-" + UUID.randomUUID(), email, true);
        assertThat(googleLogin("Test phone").getStatus()).isEqualTo(200);

        when(google.check(any(), eq(AUDIENCE))).thenReturn(GoogleIdTokenCheck.Result.unreadable());
        for (int i = 0; i < 20; i++) {
            assertThat(googleLogin("Test phone").getStatus()).isEqualTo(401);
        }
        assertThat(googleLogin("Test phone").getStatus()).isEqualTo(429);
    }

    @Test
    void googleLoginWithoutAClientIdIs503() {
        SmsDeviceLoginService unconfigured = new SmsDeviceLoginService(authService, userRepository, deviceService, limiter, google, "");
        assertThatThrownBy(() -> unconfigured.loginWithGoogle("stub-token", "Test phone", null))
                .isInstanceOfSatisfying(ApiStatusException.class, e -> assertThat(e.getStatus().value()).isEqualTo(503));
    }

    @Test
    void googleVerifierOutageIs503() throws Exception {
        when(google.check(any(), eq(AUDIENCE))).thenThrow(new GoogleIdTokenCheck.UnavailableException("down", new java.io.IOException()));
        MockHttpServletResponse response = googleLogin("Test phone");
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(mapper.readTree(response.getContentAsString()).get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void deviceTokenAuthenticatesNothingElse() throws Exception {
        String token = token();
        mockMvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sms/lines").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/sms/devices").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test
    void removedPairingEndpointsAreGone() throws Exception {
        assertThat(api.post("/api/v1/sms/pairing-code", null).getStatus()).isNotEqualTo(200);
        mockMvc.perform(post("/api/v1/sms/pair").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"ABCDEFGH\",\"deviceName\":\"x\"}")).andExpect(status().is4xxClientError());
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

        String otherEmail = "sms-login-other-" + UUID.randomUUID() + "@example.test";
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
