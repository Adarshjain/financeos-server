package com.financeos.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc calls as a signed-up user, for {@code @SpringBootTest} integration tests: signs up and
 * logs in through the real auth endpoints, then sends JSON with the session cookie.
 */
public final class ApiTestClient {

    private static final String PASSWORD = "integrationPass123!";

    private final MockMvc mockMvc;
    private final ObjectMapper mapper;
    private final Cookie session;

    private ApiTestClient(MockMvc mockMvc, ObjectMapper mapper, Cookie session) {
        this.mockMvc = mockMvc;
        this.mapper = mapper;
        this.session = session;
    }

    /** Signs up {@code email} (test invite code) and logs in. */
    public static ApiTestClient signUp(MockMvc mockMvc, ObjectMapper mapper, String email) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "email", email, "password", PASSWORD, "inviteCode", "test-invite-code"))))
                .andExpect(status().is2xxSuccessful());
        Cookie cookie = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("FINANCEOS_SESSION");
        return new ApiTestClient(mockMvc, mapper, cookie);
    }

    /** POSTs {@code body} (null = no body) and returns the raw response, whatever its status. */
    public MockHttpServletResponse post(String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path).cookie(session);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return response;
    }

    /** PATCHes {@code body} as JSON and parses the response, failing unless the status is {@code expected}. */
    public JsonNode patchJson(String path, String jsonBody, int expected) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.patch(path).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(jsonBody))
                .andReturn().getResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return json(response, expected, "PATCH " + path);
    }

    /** PUTs {@code body} as JSON and parses the response, failing unless the status is {@code expected}. */
    public JsonNode putJson(String path, Object body, int expected) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.put(path).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andReturn().getResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return json(response, expected, "PUT " + path);
    }

    /** DELETEs {@code path} and parses the response (null node when empty), failing unless the status is {@code expected}. */
    public JsonNode deleteJson(String path, int expected) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.delete(path).cookie(session))
                .andReturn().getResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return json(response, expected, "DELETE " + path);
    }

    /** GETs {@code path} and returns the raw response, whatever its status. */
    public MockHttpServletResponse get(String path) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get(path).cookie(session))
                .andReturn().getResponse();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return response;
    }

    /** POSTs and parses the JSON body, failing unless the status is {@code expected}. */
    public JsonNode postJson(String path, Object body, int expected) throws Exception {
        return json(post(path, body), expected, "POST " + path);
    }

    /** GETs and parses the JSON body, failing unless the status is {@code expected}. */
    public JsonNode getJson(String path, int expected) throws Exception {
        return json(get(path), expected, "GET " + path);
    }

    /** The {@code id} of a created resource. */
    public UUID create(String path, Object body) throws Exception {
        MockHttpServletResponse response = post(path, body);
        if (response.getStatus() / 100 != 2) {
            throw new AssertionError("POST " + path + " -> " + response.getStatus() + ": " + response.getContentAsString());
        }
        return UUID.fromString(mapper.readTree(response.getContentAsString()).get("id").asText());
    }

    private JsonNode json(MockHttpServletResponse response, int expected, String call) throws Exception {
        String body = response.getContentAsString();
        if (response.getStatus() != expected) {
            throw new AssertionError(call + " -> " + response.getStatus() + " (expected " + expected + "): " + body);
        }
        return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
    }
}
