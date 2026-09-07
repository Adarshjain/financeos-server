package com.financeos.core.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.junit.jupiter.api.Assertions.*;

class LoggingAuthenticationEntryPointTest {

    private LoggingAuthenticationEntryPoint entryPoint;
    private ObjectMapper objectMapper;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        entryPoint = new LoggingAuthenticationEntryPoint(objectMapper);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void testCommenceWithoutCookieReturns401WithReasonNoSessionCookie() throws Exception {
        MDC.put("requestId", "req-entry-point-1");

        entryPoint.commence(request, response, new BadCredentialsException("Unauthenticated"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("UNAUTHORIZED", body.get("code").asText());
        assertEquals("Authentication required", body.get("message").asText());
        assertEquals("no-session-cookie", body.get("details").get("reason").asText());
        assertEquals("req-entry-point-1", body.get("requestId").asText());
        assertTrue(body.get("errorId").isNull());
    }

    @Test
    void testCommenceWithSessionCookieReturns401WithReasonSessionExpired() throws Exception {
        MDC.put("requestId", "req-entry-point-2");
        request.setCookies(new Cookie("FINANCEOS_SESSION", "expired-token"));

        entryPoint.commence(request, response, new BadCredentialsException("Unauthenticated"));

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("UNAUTHORIZED", body.get("code").asText());
        assertEquals("session-expired", body.get("details").get("reason").asText());
        assertEquals("req-entry-point-2", body.get("requestId").asText());
        assertTrue(body.get("errorId").isNull());
    }
}
