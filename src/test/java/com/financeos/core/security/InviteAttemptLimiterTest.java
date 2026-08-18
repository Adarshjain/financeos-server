package com.financeos.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.invite.code=test-invite-code",
        "app.invite.max-failures=3",
        "app.invite.lockout-minutes=15"
})
class InviteAttemptLimiterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InviteAttemptLimiter limiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        limiter.clear();
    }

    @Test
    void testRateLimitLocksOutAfterMaxFailuresAndBlocksEvenCorrectCode() throws Exception {
        Map<String, String> wrongBody = new HashMap<>();
        wrongBody.put("email", "probe@example.com");
        wrongBody.put("password", "password123");
        wrongBody.put("inviteCode", "wrong-code");

        // 3 failed attempts
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongBody)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        // 4th attempt with wrong code -> 429 TOO_MANY_REQUESTS with Retry-After header
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongBody)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many attempts. Try again later."));

        // Attempt with CORRECT code after lockout -> also 429
        Map<String, String> correctBody = new HashMap<>();
        correctBody.put("email", "probe-correct@example.com");
        correctBody.put("password", "password123");
        correctBody.put("inviteCode", "test-invite-code");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctBody)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
