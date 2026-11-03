package com.financeos.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.UserDataCleanup;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The e2e account-detect seed: entities go through the real suggestion path for the current user; reset clears them. */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.invite.code=e2e-test-code")
class E2eAccountDetectSeedTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private UUID userId;

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, java.util.Collections.singletonList(userId));
    }

    @Test
    void seedShowsSuggestionsAndScanStateAndDeleteClearsThem() throws Exception {
        String email = "e2e-seed-" + UUID.randomUUID() + "@example.test";
        Cookie[] cookies = signUpAndLogin(email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();

        Map<String, Object> body = Map.of(
                "entities", List.of(
                        Map.of("bank", "hdfc", "last4", "1111", "kind", "CREDIT_CARD", "existsConf", "HIGH", "kindConf", "HIGH",
                                "variant", "Regalia", "variantConf", "MEDIUM", "first", "2024-03-10", "last", "2026-10-20", "evidenceMails", 9),
                        Map.of("bank", "?mail.example.test", "last4", "2222", "kind", "CREDIT_CARD", "existsConf", "HIGH", "kindConf", "HIGH")),
                "scan", Map.of("status", "IDLE", "mailsSeen", 40, "mailsTotal", 40, "firstScanDone", true),
                "firstScanCompleted", true);
        mockMvc.perform(post("/api/e2e/account-detect/seed").cookie(cookies).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/account-suggestions").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("IDLE"))
                .andExpect(jsonPath("$.scan.firstScanDone").value(true))
                .andExpect(jsonPath("$.scan.mailsSeen").value(40))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].last4").value("1111"))
                .andExpect(jsonPath("$.items[0].kindConf").value("HIGH"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_notifications WHERE user_id = ? AND title = 'We found 1 account'",
                Integer.class, userId.toString()));

        mockMvc.perform(post("/api/e2e/account-detect/seed").cookie(cookies).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entities\":[{\"bank\":\"hdfc\",\"last4\":\"3333\",\"kind\":\"NOPE\",\"existsConf\":\"HIGH\",\"kindConf\":\"HIGH\"}]}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/e2e/account-detect").cookie(cookies)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/account-suggestions").cookie(cookies))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scan.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    private Cookie[] signUpAndLogin(String email) throws Exception {
        String password = "testPassword123";
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password, "inviteCode", "e2e-test-code"))))
                .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        Cookie[] cookies = login.getResponse().getCookies();
        if (cookies == null || cookies.length == 0) {
            jakarta.servlet.http.HttpSession session = login.getRequest().getSession(false);
            if (session != null) return new Cookie[]{new Cookie("FINANCEOS_SESSION", session.getId())};
        }
        return cookies;
    }
}
