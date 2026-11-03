package com.financeos.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.ProfileChangedEvent;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.invite.code=test-invite-code"})
class ProfileControllerTest {

    @TestConfiguration
    static class Capture {
        static final List<ProfileChangedEvent> EVENTS = new CopyOnWriteArrayList<>();

        @Bean
        Object profileEventCapture() {
            return new Object() {
                @EventListener
                void on(ProfileChangedEvent e) {
                    EVENTS.add(e);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;

    private String email;

    @BeforeEach
    void signUpAndLogIn() throws Exception {
        email = "profile-" + UUID.randomUUID() + "@example.com";
        Map<String, String> creds = Map.of("email", email, "password", "password123", "inviteCode", "test-invite-code");
        mockMvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(creds))).andExpect(status().isCreated());
        Capture.EVENTS.clear();
    }

    private org.springframework.test.web.servlet.ResultActions hello(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/me/hello").with(user(email)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions patchProfile(Map<String, Object> body) throws Exception {
        return mockMvc.perform(patch("/api/v1/me/profile").with(user(email)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    @Test
    void meStartsWithHelloNotDoneAndNoBirthDate() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helloDone").value(false))
                .andExpect(jsonPath("$.birthDate").doesNotExist());
    }

    @Test
    void helloSavesProfileMarksDoneAndPublishesTheEvent() throws Exception {
        hello(Map.of("displayName", "  Asha Rao ", "birthDate", "1990-04-05", "termsAccepted", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.helloDone").value(true))
                .andExpect(jsonPath("$.displayName").value("Asha Rao"))
                .andExpect(jsonPath("$.birthDate").value("1990-04-05"))
                .andExpect(jsonPath("$.birthDateSource").value("TYPED"));

        User saved = userRepository.findByEmail(email).orElseThrow();
        assertNotNull(saved.getHelloDoneAt());
        assertNotNull(saved.getTermsAcceptedAt());
        assertNotNull(saved.getNameEditedAt());
        assertEquals(1, Capture.EVENTS.size());
        assertTrue(Capture.EVENTS.get(0).nameChanged() && Capture.EVENTS.get(0).birthDateChanged());
        assertEquals(saved.getId(), Capture.EVENTS.get(0).userId());
    }

    @Test
    void helloWithEmptyValuesStillCompletes() throws Exception {
        hello(Map.of("termsAccepted", true)).andExpect(status().isOk()).andExpect(jsonPath("$.helloDone").value(true));
        assertNull(userRepository.findByEmail(email).orElseThrow().getBirthDate());
    }

    @Test
    void helloRequiresTerms() throws Exception {
        hello(Map.of("displayName", "Asha", "termsAccepted", false)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/auth/me").with(user(email))).andExpect(jsonPath("$.helloDone").value(false));
    }

    @Test
    void helloRejectsAnInvalidBirthDate() throws Exception {
        hello(Map.of("birthDate", AppTime.today().plusDays(1).toString(), "termsAccepted", true))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profilePatchValidatesAndSaves() throws Exception {
        patchProfile(Map.of("displayName", "   ")).andExpect(status().isBadRequest());
        patchProfile(Map.of("displayName", "x".repeat(121))).andExpect(status().isBadRequest());
        patchProfile(Map.of("birthDate", "1899-01-01")).andExpect(status().isBadRequest());
        patchProfile(Map.of("birthDate", "2999-01-01")).andExpect(status().isBadRequest());
        patchProfile(Map.of("birthDate", "nope")).andExpect(status().isBadRequest());

        patchProfile(Map.of("displayName", "Asha R", "birthDate", "--04-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Asha R"))
                .andExpect(jsonPath("$.birthDate").value("--04-05"));
        assertEquals(1, Capture.EVENTS.size());
    }

    @Test
    void anUnchangedPatchPublishesNothingAndBlankClearsTheBirthDate() throws Exception {
        patchProfile(Map.of("birthDate", "1990-04-05")).andExpect(status().isOk());
        Capture.EVENTS.clear();
        patchProfile(Map.of("birthDate", "1990-04-05")).andExpect(status().isOk());
        assertTrue(Capture.EVENTS.isEmpty());
        patchProfile(Map.of("birthDate", "")).andExpect(status().isOk()).andExpect(jsonPath("$.birthDate").doesNotExist());
    }

    @Test
    void profileEndpointsNeedASession() throws Exception {
        mockMvc.perform(patch("/api/v1/me/profile").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }
}
