package com.financeos.api.applock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.MutableClock;
import com.financeos.support.TestAuthenticator;
import com.financeos.support.UserDataCleanup;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** App lock end to end over MockMvc: real filter chain, real WebAuthn verification, a moving clock. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AppLockFlowIntegrationTest.ClockConfig.class)
class AppLockFlowIntegrationTest {

    private static final String BASE = "/api/v1/app-lock";
    private static final String RP_ID = "localhost";
    private static final String ORIGIN = "https://localhost:6970";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock appLockTestClock() {
            return new MutableClock(Instant.parse("2026-10-23T10:00:00Z"));
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private Clock clock;

    private final List<UUID> userIds = new ArrayList<>();
    private String email;
    private ApiTestClient api;
    private UUID userId;
    private TestAuthenticator authenticator;

    @BeforeEach
    void setUp() throws Exception {
        email = "applock-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        userIds.add(userId);
        authenticator = new TestAuthenticator(RP_ID, ORIGIN);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, userIds);
    }

    private void advance(long seconds) {
        ((MutableClock) clock).advance(Duration.ofSeconds(seconds));
    }

    private String enrol(ApiTestClient client, TestAuthenticator auth, int lockAfter) throws Exception {
        JsonNode options = client.postJson(BASE + "/registration/options", Map.of("lockAfterSeconds", lockAfter), 200);
        JsonNode credential = auth.create(options, true);
        JsonNode verified = client.postJson(BASE + "/registration/verify",
                Map.of("credential", credential, "label", "Test phone"), 200);
        return verified.get("deviceId").asText();
    }

    private MockHttpServletResponse unlock(ApiTestClient client, TestAuthenticator auth, boolean userVerified)
            throws Exception {
        JsonNode options = client.postJson(BASE + "/unlock/options", null, 200);
        return client.post(BASE + "/unlock/verify", Map.of("credential", auth.get(options, userVerified)));
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    private long deviceRows(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_lock_devices WHERE user_id = ?", Long.class, id.toString());
    }

    private void assertLocked(ApiTestClient client) throws Exception {
        for (String path : List.of("/api/v1/accounts", "/api/v1/auth/me")) {
            MockHttpServletResponse r = client.get(path);
            assertEquals(423, r.getStatus(), path);
            assertEquals("APP_LOCKED", body(r).get("code").asText());
        }
    }

    // (a) + (b) + (e)
    @Test
    void enrolThenIdleLocksThenUnlockOpensAgain() throws Exception {
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
        enrol(api, authenticator, 60);
        assertEquals(1, deviceRows(userId));
        assertEquals(200, api.get("/api/v1/accounts").getStatus());

        advance(61);

        assertLocked(api);
        // Open while locked.
        JsonNode status = api.getJson(BASE + "/status", 200);
        assertTrue(status.get("armed").asBoolean());
        assertTrue(status.get("locked").asBoolean());
        assertEquals(60, status.get("lockAfterSeconds").asInt());
        assertEquals(RP_ID, status.get("rpId").asText());
        assertEquals(15, status.get("heartbeatSeconds").asInt());
        assertEquals(423, api.post(BASE + "/heartbeat", null).getStatus(), "a heartbeat cannot unlock");

        assertEquals(204, unlock(api, authenticator, true).getStatus());

        assertEquals(200, api.get("/api/v1/accounts").getStatus());
        assertEquals(200, api.get("/api/v1/auth/me").getStatus());
        assertFalse(api.getJson(BASE + "/status", 200).get("locked").asBoolean());
        assertTrue(jdbc.queryForObject("SELECT signature_count FROM app_lock_devices WHERE user_id = ?",
                Long.class, userId.toString()) >= 1);
    }

    @Test
    void heartbeatsKeepItOpenAndOrdinaryRequestsDoNot() throws Exception {
        enrol(api, authenticator, 60);
        advance(50);
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
        advance(50);
        assertEquals(423, api.get("/api/v1/accounts").getStatus(), "100s since enrolment, GETs do not count");
    }

    @Test
    void heartbeatKeepsTheSessionOpen() throws Exception {
        enrol(api, authenticator, 60);
        for (int i = 0; i < 4; i++) {
            advance(50);
            assertEquals(204, api.post(BASE + "/heartbeat", null).getStatus());
        }
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
    }

    @Test
    void lockNowLocksAtOnceAndIsIdempotent() throws Exception {
        enrol(api, authenticator, 900);
        advance(3);
        assertEquals(204, api.post(BASE + "/lock", null).getStatus());
        assertEquals(204, api.post(BASE + "/lock", null).getStatus());
        assertLocked(api);
    }

    @Test
    void lockBeaconRightAfterAnUnlockIsIgnored() throws Exception {
        enrol(api, authenticator, 0);
        advance(3);
        api.post(BASE + "/lock", null);
        assertEquals(204, unlock(api, authenticator, true).getStatus());

        advance(1);
        assertEquals(204, api.post(BASE + "/lock", null).getStatus());
        assertEquals(200, api.get("/api/v1/accounts").getStatus(), "the page-leave beacon must not re-lock");

        advance(2);
        api.post(BASE + "/lock", null);
        assertLocked(api);
    }

    @Test
    void lockNowIsHarmlessWhenAppLockIsOff() throws Exception {
        assertEquals(204, api.post(BASE + "/lock", null).getStatus());
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
        JsonNode status = api.getJson(BASE + "/status", 200);
        assertFalse(status.get("armed").asBoolean());
        assertFalse(status.get("locked").asBoolean());
    }

    @Test
    void immediatelyUsesTheBackstopWithoutHeartbeats() throws Exception {
        enrol(api, authenticator, 0);
        advance(29);
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
        advance(2);
        assertLocked(api);
    }

    @Test
    void settingsChangeTheLimitAndRejectOtherValues() throws Exception {
        enrol(api, authenticator, 60);
        api.patchJson(BASE + "/settings", "{\"lockAfterSeconds\":45}", 400);
        assertEquals(60, api.getJson(BASE + "/status", 200).get("lockAfterSeconds").asInt());

        api.patchJson(BASE + "/settings", "{\"lockAfterSeconds\":900}", 204);
        assertEquals(900, api.getJson(BASE + "/status", 200).get("lockAfterSeconds").asInt());
        assertEquals(900, jdbc.queryForObject("SELECT lock_after_seconds FROM app_lock_devices WHERE user_id = ?",
                Integer.class, userId.toString()));
        advance(200);
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
    }

    // (c)
    @Test
    void unlockIsRejectedWithoutUserVerification() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        MockHttpServletResponse r = unlock(api, authenticator, false);
        assertEquals(403, r.getStatus());
        assertEquals("APP_LOCK_UNLOCK_FAILED", body(r).get("code").asText());
        assertLocked(api);
    }

    @Test
    void unlockIsRejectedForAnotherUsersCredential() throws Exception {
        enrol(api, authenticator, 60);
        String otherEmail = "applock-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        userIds.add(userRepository.findByEmail(otherEmail).orElseThrow().getId());
        TestAuthenticator otherAuth = new TestAuthenticator(RP_ID, ORIGIN);
        enrol(other, otherAuth, 60);
        advance(61);

        MockHttpServletResponse r = unlock(api, otherAuth, true);

        assertEquals(403, r.getStatus());
        assertEquals("APP_LOCK_UNLOCK_FAILED", body(r).get("code").asText());
        assertLocked(api);
    }

    @Test
    void unlockIsRejectedForAWrongRelyingParty() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        TestAuthenticator wrongRp = new TestAuthenticator("evil.example", ORIGIN);
        assertEquals(403, unlock(api, wrongRp, true).getStatus());
    }

    @Test
    void aReplayedAssertionIsRejected() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        JsonNode options = api.postJson(BASE + "/unlock/options", null, 200);
        JsonNode assertion = authenticator.get(options, true);
        assertEquals(204, api.post(BASE + "/unlock/verify", Map.of("credential", assertion)).getStatus());

        advance(3);
        api.post(BASE + "/lock", null);
        MockHttpServletResponse replay = api.post(BASE + "/unlock/verify", Map.of("credential", assertion));

        assertEquals(403, replay.getStatus());
        assertLocked(api);
    }

    @Test
    void anExpiredChallengeIsRejected() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        JsonNode options = api.postJson(BASE + "/unlock/options", null, 200);
        JsonNode assertion = authenticator.get(options, true);
        advance(301);

        assertEquals(403, api.post(BASE + "/unlock/verify", Map.of("credential", assertion)).getStatus());
        assertLocked(api);
    }

    @Test
    void aConsumedChallengeCannotBeReusedByAGoodAnswer() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        JsonNode options = api.postJson(BASE + "/unlock/options", null, 200);
        JsonNode assertion = authenticator.get(options, true);
        // Consume the pending challenge with a bad answer, then try the good one.
        api.post(BASE + "/unlock/verify", Map.of("credential", authenticator.get(options, false)));
        assertEquals(403, api.post(BASE + "/unlock/verify", Map.of("credential", assertion)).getStatus());
    }

    @Test
    void enrolmentWithoutUserVerificationIsRejected() throws Exception {
        JsonNode options = api.postJson(BASE + "/registration/options", Map.of("lockAfterSeconds", 60), 200);
        api.postJson(BASE + "/registration/verify", Map.of("credential", authenticator.create(options, false)), 400);
        assertEquals(0, deviceRows(userId));
        assertFalse(api.getJson(BASE + "/status", 200).get("armed").asBoolean());
    }

    @Test
    void registrationChallengeIsSingleUse() throws Exception {
        JsonNode options = api.postJson(BASE + "/registration/options", Map.of("lockAfterSeconds", 60), 200);
        JsonNode credential = authenticator.create(options, true);
        api.postJson(BASE + "/registration/verify", Map.of("credential", credential), 200);
        // The challenge was single use.
        api.postJson(BASE + "/registration/verify", Map.of("credential", credential), 400);
    }

    @Test
    void registrationOptionsAskForAPlatformPasskeyWithUserVerification() throws Exception {
        JsonNode options = api.postJson(BASE + "/registration/options", Map.of("lockAfterSeconds", 60), 200);
        JsonNode pk = options.get("publicKey");
        assertEquals(RP_ID, pk.get("rp").get("id").asText());
        assertEquals("platform", pk.get("authenticatorSelection").get("authenticatorAttachment").asText());
        assertEquals("required", pk.get("authenticatorSelection").get("userVerification").asText());
        assertEquals(email, pk.get("user").get("name").asText());
        assertNotEquals(userId.toString(), pk.get("user").get("id").asText());
        api.postJson(BASE + "/registration/options", Map.of("lockAfterSeconds", 7), 400);
    }

    // (d)
    @Test
    void fifthFailedUnlockEndsTheSession() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        for (int i = 1; i <= 4; i++) {
            MockHttpServletResponse r = unlock(api, authenticator, false);
            assertEquals(403, r.getStatus());
            assertEquals("APP_LOCK_UNLOCK_FAILED", body(r).get("code").asText(), "attempt " + i);
        }
        MockHttpServletResponse fifth = unlock(api, authenticator, false);
        assertEquals(401, fifth.getStatus());
        assertEquals("UNAUTHORIZED", body(fifth).get("code").asText());

        MockHttpServletResponse after = api.get("/api/v1/accounts");
        assertEquals(401, after.getStatus());
        assertEquals("UNAUTHORIZED", body(after).get("code").asText());
    }

    @Test
    void aSuccessfulUnlockResetsTheFailureCount() throws Exception {
        enrol(api, authenticator, 60);
        for (int round = 0; round < 3; round++) {
            advance(61);
            for (int i = 0; i < 3; i++) {
                assertEquals(403, unlock(api, authenticator, false).getStatus());
            }
            assertEquals(204, unlock(api, authenticator, true).getStatus());
        }
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
    }

    // (e)
    @Test
    void logoutWorksWhileLocked() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        assertLocked(api);
        assertEquals(200, api.post("/api/v1/auth/logout", null).getStatus());
        assertEquals(401, api.get("/api/v1/accounts").getStatus());
    }

    // (f)
    @Test
    void loginWithTheDeviceCookieIsArmedAndUnlocked() throws Exception {
        String deviceId = enrol(api, authenticator, 300);

        ApiTestClient withCookie = ApiTestClient.login(mockMvc, mapper, email, new Cookie("FINANCEOS_DEVICE", deviceId));
        JsonNode status = withCookie.getJson(BASE + "/status", 200);
        assertTrue(status.get("armed").asBoolean());
        assertFalse(status.get("locked").asBoolean());
        assertEquals(300, status.get("lockAfterSeconds").asInt());
        assertEquals(200, withCookie.get("/api/v1/accounts").getStatus());
        advance(301);
        assertLocked(withCookie);

        ApiTestClient without = ApiTestClient.login(mockMvc, mapper, email);
        assertFalse(without.getJson(BASE + "/status", 200).get("armed").asBoolean());
        advance(100_000);
        assertEquals(200, without.get("/api/v1/accounts").getStatus());
    }

    @Test
    void aDeviceCookieOfAnotherUserOrGarbageDoesNotArm() throws Exception {
        String deviceId = enrol(api, authenticator, 60);
        String otherEmail = "applock-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        userIds.add(userRepository.findByEmail(otherEmail).orElseThrow().getId());

        ApiTestClient stolen = ApiTestClient.login(mockMvc, mapper, otherEmail, new Cookie("FINANCEOS_DEVICE", deviceId));
        assertFalse(stolen.getJson(BASE + "/status", 200).get("armed").asBoolean());
        ApiTestClient garbage = ApiTestClient.login(mockMvc, mapper, otherEmail, new Cookie("FINANCEOS_DEVICE", "nope"));
        assertFalse(garbage.getJson(BASE + "/status", 200).get("armed").asBoolean());
    }

    @Test
    void aSecondDeviceDoesNotReplaceTheFirst() throws Exception {
        String first = enrol(api, authenticator, 60);
        ApiTestClient secondSession = ApiTestClient.login(mockMvc, mapper, email);
        TestAuthenticator second = new TestAuthenticator(RP_ID, ORIGIN);
        String secondId = enrol(secondSession, second, 300);

        assertNotEquals(first, secondId);
        assertEquals(2, deviceRows(userId));
        advance(61);
        assertEquals(204, unlock(api, authenticator, true).getStatus());
    }

    @Test
    void reEnrollingWhileArmedIsRefused() throws Exception {
        enrol(api, authenticator, 60);
        MockHttpServletResponse r = api.post(BASE + "/registration/options", Map.of("lockAfterSeconds", 900));

        assertEquals(409, r.getStatus());
        assertEquals("APP_LOCK_ALREADY_ON", body(r).get("code").asText());
        assertEquals(1, deviceRows(userId));
        JsonNode status = api.getJson(BASE + "/status", 200);
        assertTrue(status.get("armed").asBoolean());
        assertEquals(60, status.get("lockAfterSeconds").asInt());
        assertEquals(403, disable(api, new TestAuthenticator(RP_ID, ORIGIN), true).getStatus());
        assertEquals(204, disable(api, authenticator, true).getStatus());
    }

    @Test
    void reEnrollingAfterTheRowIsGoneWorks() throws Exception {
        String first = enrol(api, authenticator, 60);
        jdbc.update("DELETE FROM app_lock_devices WHERE user_id = ?", userId.toString());
        String second = enrol(api, new TestAuthenticator(RP_ID, ORIGIN), 900);

        assertNotEquals(first, second);
        assertEquals(1, deviceRows(userId));
        assertEquals(900, api.getJson(BASE + "/status", 200).get("lockAfterSeconds").asInt());
    }

    private MockHttpServletResponse disable(ApiTestClient client, TestAuthenticator auth, boolean userVerified)
            throws Exception {
        JsonNode options = client.postJson(BASE + "/unlock/options", null, 200);
        return client.post(BASE + "/disable", Map.of("credential", auth.get(options, userVerified)));
    }

    // (g)
    @Test
    void disablingWithAGoodAssertionRemovesTheRowAndDisarms() throws Exception {
        enrol(api, authenticator, 60);
        assertEquals(204, disable(api, authenticator, true).getStatus());

        assertEquals(0, deviceRows(userId));
        assertFalse(api.getJson(BASE + "/status", 200).get("armed").asBoolean());
        advance(100_000);
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
    }

    @Test
    void disablingIsRefusedWhileLocked() throws Exception {
        enrol(api, authenticator, 60);
        advance(61);
        MockHttpServletResponse r = api.post(BASE + "/disable", Map.of("credential", Map.of()));
        assertEquals(423, r.getStatus());
        assertEquals(1, deviceRows(userId));
    }

    @Test
    void disablingWithABadAssertionIsRefusedAndKeepsItArmed() throws Exception {
        enrol(api, authenticator, 60);
        MockHttpServletResponse r = disable(api, authenticator, false);

        assertEquals(403, r.getStatus());
        assertEquals("APP_LOCK_UNLOCK_FAILED", body(r).get("code").asText());
        assertEquals(1, deviceRows(userId));
        assertTrue(api.getJson(BASE + "/status", 200).get("armed").asBoolean());
    }

    @Test
    void disablingWithoutStartingACeremonyIsRefused() throws Exception {
        enrol(api, authenticator, 60);
        JsonNode options = api.postJson(BASE + "/unlock/options", null, 200);
        JsonNode assertion = authenticator.get(options, true);
        // Consume the challenge, then answer a ceremony nobody started.
        assertEquals(204, api.post(BASE + "/unlock/verify", Map.of("credential", assertion)).getStatus());
        MockHttpServletResponse r = api.post(BASE + "/disable", Map.of("credential", assertion));

        assertEquals(403, r.getStatus());
        assertEquals(1, deviceRows(userId));
        assertTrue(api.getJson(BASE + "/status", 200).get("armed").asBoolean());
    }

    @Test
    void disablingWithAnotherUsersCredentialIsRefused() throws Exception {
        enrol(api, authenticator, 60);
        String otherEmail = "applock-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        userIds.add(userRepository.findByEmail(otherEmail).orElseThrow().getId());
        TestAuthenticator otherAuth = new TestAuthenticator(RP_ID, ORIGIN);
        enrol(other, otherAuth, 60);

        assertEquals(403, disable(api, otherAuth, true).getStatus());
        assertEquals(1, deviceRows(userId));
        assertTrue(api.getJson(BASE + "/status", 200).get("armed").asBoolean());
    }

    @Test
    void fifthFailedDisableEndsTheSession() throws Exception {
        enrol(api, authenticator, 60);
        for (int i = 1; i <= 4; i++) {
            assertEquals(403, disable(api, authenticator, false).getStatus(), "attempt " + i);
        }
        MockHttpServletResponse fifth = disable(api, authenticator, false);
        assertEquals(401, fifth.getStatus());
        assertEquals("UNAUTHORIZED", body(fifth).get("code").asText());
        assertEquals(401, api.get("/api/v1/accounts").getStatus());
        assertEquals(1, deviceRows(userId));
    }

    @Test
    void disablingAnUnarmedSessionJustSucceeds() throws Exception {
        assertEquals(204, api.post(BASE + "/disable", Map.of("credential", Map.of())).getStatus());
        assertFalse(api.getJson(BASE + "/status", 200).get("armed").asBoolean());
    }

    @Test
    void disablingWithAMissingDeviceRowDisarms() throws Exception {
        enrol(api, authenticator, 60);
        jdbc.update("DELETE FROM app_lock_devices WHERE user_id = ?", userId.toString());

        assertEquals(204, api.post(BASE + "/disable", Map.of("credential", Map.of())).getStatus());
        assertFalse(api.getJson(BASE + "/status", 200).get("armed").asBoolean());
    }

    @Test
    void unlockWithAMissingDeviceRowIsNeverGranted() throws Exception {
        enrol(api, authenticator, 60);
        jdbc.update("DELETE FROM app_lock_devices WHERE user_id = ?", userId.toString());
        advance(61);

        MockHttpServletResponse r = api.post(BASE + "/unlock/options", null);

        assertEquals(409, r.getStatus());
        assertEquals("APP_LOCK_DEVICE_MISSING", body(r).get("code").asText());
        assertLocked(api);
    }

    @Test
    void anUnarmedSessionIsUntouched() throws Exception {
        advance(1_000_000);
        assertEquals(200, api.get("/api/v1/accounts").getStatus());
        assertEquals(204, api.post(BASE + "/heartbeat", null).getStatus());
        assertEquals(409, api.post(BASE + "/unlock/options", null).getStatus());
    }
}
