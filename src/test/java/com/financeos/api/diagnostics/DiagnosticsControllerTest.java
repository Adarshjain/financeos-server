package com.financeos.api.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.diagnostics.LokiQueryClient;
import com.financeos.core.exception.ApiStatusException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.emails=admin@example.test",
        "app.invite.code=test-invite-code"
})
class DiagnosticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LokiQueryClient lokiClient;

    private Cookie adminCookie;
    private Cookie nonAdminCookie;

    @BeforeEach
    void setUp() throws Exception {
        doCallRealMethod().when(lokiClient).validateRef(any());

        // Create Admin user
        adminCookie = authenticateUser("admin@example.test", "adminPass123!");
        // Create Non-Admin user
        nonAdminCookie = authenticateUser("user@example.test", "userPass123!");
    }

    private Cookie authenticateUser(String email, String password) throws Exception {
        // Sign up with invite code
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email,
                        "password", password,
                        "inviteCode", "test-invite-code"
                ))));

        // Log in to obtain FINANCEOS_SESSION cookie
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        return loginResult.getResponse().getCookie("FINANCEOS_SESSION");
    }

    @Test
    void testUnauthenticatedReturns401JsonWithRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/diagnostics/lookup").param("ref", "E2EERR01"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.details.reason").value("no-session-cookie"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void testAuthenticatedNonAdminReturns403Forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/diagnostics/lookup")
                        .param("ref", "E2EERR01")
                        .cookie(nonAdminCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/diagnostics/lookup/raw")
                        .param("ref", "E2EERR01")
                        .cookie(nonAdminCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void testAuthMeReturnsAdminTrueForAdminAndFalseForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin@example.test"))
                .andExpect(jsonPath("$.admin").value(true));

        mockMvc.perform(get("/api/v1/auth/me").cookie(nonAdminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.test"))
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void testAdminLookupReturns200WithDiagnosticsData() throws Exception {
        String serverLine = "{\"event\":\"request.failed\",\"errorId\":\"E2EERR01\",\"requestId\":\"e2ereq0000000000001a\",\"message\":\"crash\"}";
        LokiQueryClient.LokiLogLine l1 = new LokiQueryClient.LokiLogLine(
                1700000000000000000L, Instant.now(), "server", Map.of("level", "ERROR"), serverLine
        );

        String httpLine = "{\"event\":\"http.request\",\"requestId\":\"e2ereq0000000000001a\",\"method\":\"GET\",\"route\":\"/api/v1/accounts\",\"status\":500,\"durationMs\":25,\"version\":\"1.0.0\"}";
        LokiQueryClient.LokiLogLine l2 = new LokiQueryClient.LokiLogLine(
                1700000000010000000L, Instant.now(), "server", Map.of("level", "INFO"), httpLine
        );

        when(lokiClient.query(contains("errorId=\"E2EERR01\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l1), false));
        when(lokiClient.query(contains("requestId=\"e2ereq0000000000001a\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l2), false));
        when(lokiClient.query(contains("kind=~"), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        mockMvc.perform(get("/api/v1/diagnostics/lookup")
                        .param("ref", "E2EERR01")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.ref").value("E2EERR01"))
                .andExpect(jsonPath("$.refType").value("errorId"))
                .andExpect(jsonPath("$.requestId").value("e2ereq0000000000001a"))
                .andExpect(jsonPath("$.rootCause.kind").value("SERVER_EXCEPTION"))
                .andExpect(jsonPath("$.request.route").value("/api/v1/accounts"))
                .andExpect(jsonPath("$.request.status").value(500))
                .andExpect(jsonPath("$.timeline").isArray());
    }

    @Test
    void testBadRefReturns400ValidationError() throws Exception {
        mockMvc.perform(get("/api/v1/diagnostics/lookup")
                        .param("ref", "bad$ref")
                        .cookie(adminCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void testTypeOverrideHonoured() throws Exception {
        when(lokiClient.query(contains("requestId=\"E2EERR01\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(contains("kind=~"), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        mockMvc.perform(get("/api/v1/diagnostics/lookup")
                        .param("ref", "E2EERR01")
                        .param("type", "requestId")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refType").value("requestId"))
                .andExpect(jsonPath("$.found").value(false));

        verify(lokiClient, atLeastOnce()).query(contains("requestId=\"E2EERR01\""), any(), any(), anyInt());
    }

    @Test
    void testInvalidSinceIsoReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/diagnostics/lookup")
                        .param("ref", "E2EERR01")
                        .param("since", "not-a-valid-iso-date")
                        .cookie(adminCookie))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testAdminRawLookupReturns200List() throws Exception {
        String lineText = "{\"log\":\"raw text\"}";
        LokiQueryClient.LokiLogLine l1 = new LokiQueryClient.LokiLogLine(
                1700000000000000000L, Instant.now(), "server", Map.of("env", "test"), lineText
        );

        when(lokiClient.query(contains("requestId=\"e2ereq0000000000001a\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l1), false));
        when(lokiClient.query(contains("kind=~"), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        mockMvc.perform(get("/api/v1/diagnostics/lookup/raw")
                        .param("ref", "e2ereq0000000000001a")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].line").value(lineText))
                .andExpect(jsonPath("$[0].source").value("server"))
                .andExpect(jsonPath("$[0].labels.env").value("test"));
    }

    @Test
    void testLoki401Becomes503DiagnosticsUnavailable() throws Exception {
        when(lokiClient.query(anyString(), any(), any(), anyInt()))
                .thenThrow(new ApiStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "DIAGNOSTICS_UNAVAILABLE",
                        "Loki credentials lack read scope. Please configure LOKI_QUERY_TOKEN with logs:read permissions."
                ));

        mockMvc.perform(get("/api/v1/diagnostics/lookup")
                        .param("ref", "e2ereq0000000000001a")
                        .cookie(adminCookie))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DIAGNOSTICS_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("LOKI_QUERY_TOKEN")));
    }
}
