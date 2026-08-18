package com.financeos.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.InviteAttemptLimiter;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.invite.code=test-invite-code")
class SignupInviteCodeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InviteAttemptLimiter limiter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        limiter.reset();
    }

    @Test
    void testCorrectInviteCodeCreatesUser() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("email", "valid-user@example.com");
        body.put("password", "password123");
        body.put("inviteCode", "test-invite-code");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("valid-user@example.com"));

        assertTrue(userRepository.findByEmail("valid-user@example.com").isPresent());
    }

    @Test
    void testWrongInviteCodeReturns400ValidationError() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("email", "wrong-code-user@example.com");
        body.put("password", "password123");
        body.put("inviteCode", "wrong-code");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid invite code"));
    }

    @Test
    void testMissingInviteCodeReturns400ValidationError() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("email", "missing-code@example.com");
        body.put("password", "password123");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void testWrongInviteCodeOnExistingEmailReturns400Not409() throws Exception {
        User existingUser = userRepository.save(new User("existing@example.com", passwordEncoder.encode("password123")));

        Map<String, String> body = new HashMap<>();
        body.put("email", existingUser.getEmail());
        body.put("password", "password123");
        body.put("inviteCode", "bad-invite-code");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid invite code"));
    }

    @Test
    void testCorrectInviteCodeOnExistingEmailReturns409() throws Exception {
        User existingUser = userRepository.save(new User("dup-check@example.com", passwordEncoder.encode("password123")));

        Map<String, String> body = new HashMap<>();
        body.put("email", existingUser.getEmail());
        body.put("password", "password123");
        body.put("inviteCode", "test-invite-code");

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE"));
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.invite.code=")
    class UnsetInviteCodeTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private InviteAttemptLimiter limiter;

        @BeforeEach
        void setUp() {
            limiter.reset();
        }

        @Test
        void testBlankInviteCodeRefusesAllSignups() throws Exception {
            Map<String, String> body = new HashMap<>();
            body.put("email", "anyone@example.com");
            body.put("password", "password123");
            body.put("inviteCode", "test-invite-code");

            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("Signups are currently closed"));
        }
    }
}
