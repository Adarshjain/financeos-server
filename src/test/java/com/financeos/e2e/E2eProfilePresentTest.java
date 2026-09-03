package com.financeos.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.llm.LlmClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.invite.code=e2e-test-code")
class E2eProfilePresentTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmClient llmClient;

    @Test
    void llmClientPrimaryIsScriptedLlmClient() {
        assertInstanceOf(ScriptedLlmClient.class, llmClient);
    }

    @Test
    void coverageTracksAuthMeEndpoint() throws Exception {
        // 1. Reset coverage first
        Cookie[] sessionCookies = signUpAndLogin("e2e-coverage-test@example.com");

        // Reset coverage to start clean
        mockMvc.perform(post("/api/e2e/coverage/reset")
                        .cookie(sessionCookies))
                .andExpect(status().isNoContent());

        // 2. Call GET /api/v1/auth/me
        mockMvc.perform(get("/api/v1/auth/me")
                        .cookie(sessionCookies))
                .andExpect(status().isOk());

        // 3. Check coverage
        MvcResult coverageResult = mockMvc.perform(get("/api/e2e/coverage")
                        .cookie(sessionCookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").isArray())
                .andReturn();

        String body = coverageResult.getResponse().getContentAsString();
        // The /api/v1/auth/me GET should appear with ok >= 1
        // /api/e2e/** endpoints should NOT appear (excluded)
        assertTrue(body.contains("/api/v1/auth/me"), "Coverage should include /api/v1/auth/me");
        assertFalse(body.contains("/api/e2e/"), "Coverage should exclude /api/e2e/** endpoints");
    }

    private Cookie[] signUpAndLogin(String email) throws Exception {
        String password = "testPassword123";

        // Sign up
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password,
                                "inviteCode", "e2e-test-code"
                        ))))
                .andExpect(status().isCreated());

        // Login and capture session
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpServletResponse loginResponse = loginResult.getResponse();
        Cookie[] cookies = loginResponse.getCookies();
        // If no explicit cookies, use the session from the MockMvc
        if (cookies == null || cookies.length == 0) {
            // MockMvc stores the session internally; we can get it from the request
            jakarta.servlet.http.HttpSession session = loginResult.getRequest().getSession(false);
            if (session != null) {
                Cookie sessionCookie = new Cookie("FINANCEOS_SESSION", session.getId());
                return new Cookie[]{sessionCookie};
            }
        }
        return cookies;
    }

    private static void assertInstanceOf(Class<?> expected, Object actual) {
        assertTrue(expected.isInstance(actual),
                "Expected " + expected.getSimpleName() + " but got " + actual.getClass().getSimpleName());
    }
}
