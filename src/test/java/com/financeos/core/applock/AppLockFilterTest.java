package com.financeos.core.applock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class AppLockFilterTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private MutableClock clock;
    private AppLockService service;
    private AppLockFilter filter;
    private MockHttpSession session;
    private final AtomicInteger chainCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-23T10:00:00Z"));
        service = new AppLockService(new AppLockProperties(), clock);
        filter = new AppLockFilter(service, mapper);
        session = new MockHttpSession();
        signIn();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signIn() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("a@example.test", null, AuthorityUtils.NO_AUTHORITIES));
    }

    private MockHttpServletResponse call(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainCalls.incrementAndGet());
        return response;
    }

    private void armAndLock() {
        service.arm(session, "device-1", 60);
        clock.advance(Duration.ofSeconds(61));
    }

    @Test
    void lockedSessionGetsTheExact423Body() throws Exception {
        armAndLock();

        MockHttpServletResponse response = call("GET", "/api/v1/accounts");

        assertEquals(423, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals("APP_LOCKED", body.get("code").asText());
        assertEquals("FinanceOS is locked", body.get("message").asText());
        assertTrue(body.get("details").isObject() && body.get("details").isEmpty());
        assertEquals(Instant.parse("2026-10-23T10:01:01Z"), Instant.parse(body.get("timestamp").asText()));
        assertEquals(0, chainCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/logout", "/api/v1/auth/login", "/api/v1/auth/signup",
            "/api/v1/auth/google/start", "/api/v1/auth/google/callback",
            "/api/v1/app-lock/status", "/api/v1/app-lock/unlock/options", "/api/v1/app-lock/unlock/verify",
            "/api/v1/app-lock/lock"})
    void openPathsPassWhileLocked(String path) throws Exception {
        armAndLock();
        assertEquals(200, call("POST", path).getStatus());
        assertEquals(1, chainCalls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/auth/me", "/api/v1/app-lock/heartbeat", "/api/v1/app-lock/settings",
            "/api/v1/app-lock/registration/options", "/api/v1/app-lock", "/api/v1/auth/me/delete"})
    void everythingElseIsRefusedWhileLocked(String path) throws Exception {
        armAndLock();
        assertEquals(423, call("GET", path).getStatus());
        assertEquals(0, chainCalls.get());
    }

    @Test
    void unauthenticatedRequestsPassThrough() throws Exception {
        armAndLock();
        SecurityContextHolder.clearContext();
        assertEquals(200, call("GET", "/api/v1/accounts").getStatus());

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertEquals(200, call("GET", "/api/v1/accounts").getStatus());
        assertEquals(2, chainCalls.get());
    }

    @Test
    void unarmedSessionPassesAndIsNeverWrittenTo() throws Exception {
        clock.advance(Duration.ofDays(30));
        assertEquals(200, call("GET", "/api/v1/accounts").getStatus());
        assertFalse(session.getAttributeNames().asIterator().hasNext());
    }

    @Test
    void requestWithNoSessionPassesWithoutCreatingOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> chainCalls.incrementAndGet());
        assertEquals(200, response.getStatus());
        assertEquals(1, chainCalls.get());
        assertEquals(null, request.getSession(false));
    }

    @Test
    void optionsPreflightPassesWhileLocked() throws Exception {
        armAndLock();
        assertEquals(200, call("OPTIONS", "/api/v1/accounts").getStatus());
    }

    @Test
    void aGetDoesNotExtendTheTime() throws Exception {
        service.arm(session, "device-1", 60);
        clock.advance(Duration.ofSeconds(40));
        assertEquals(200, call("GET", "/api/v1/accounts").getStatus());
        clock.advance(Duration.ofSeconds(30));
        assertEquals(423, call("GET", "/api/v1/accounts").getStatus());
    }

    @Test
    void lockStaysUntilUnlockEvenAfterAHeartbeatAttempt() throws Exception {
        armAndLock();
        assertEquals(423, call("POST", "/api/v1/app-lock/heartbeat").getStatus());
        assertEquals(423, call("GET", "/api/v1/accounts").getStatus());
        service.unlock(session);
        assertEquals(200, call("GET", "/api/v1/accounts").getStatus());
    }
}
