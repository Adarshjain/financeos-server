package com.financeos.core.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoggingAccessDeniedHandlerTest {

    private LoggingAccessDeniedHandler accessDeniedHandler;
    private ObjectMapper objectMapper;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        accessDeniedHandler = new LoggingAccessDeniedHandler(objectMapper);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void testHandleUnauthenticatedReturns403WithReasonUnauthenticated() throws Exception {
        MDC.put("requestId", "req-denied-1");

        accessDeniedHandler.handle(request, response, new AccessDeniedException("Access denied"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("FORBIDDEN", body.get("code").asText());
        assertEquals("Access denied: unauthenticated", body.get("message").asText());
        assertEquals("unauthenticated", body.get("details").get("reason").asText());
        assertEquals("req-denied-1", body.get("requestId").asText());
        assertTrue(body.get("errorId").isNull());
    }

    @Test
    void testHandleAnonymousUserReturns403WithReasonUnauthenticated() throws Exception {
        MDC.put("requestId", "req-denied-anon");
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        accessDeniedHandler.handle(request, response, new AccessDeniedException("Access denied"));

        assertEquals(403, response.getStatus());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("FORBIDDEN", body.get("code").asText());
        assertEquals("unauthenticated", body.get("details").get("reason").asText());
        assertEquals("req-denied-anon", body.get("requestId").asText());
    }

    @Test
    void testHandleAuthenticatedUserReturns403WithReasonInsufficientAuthority() throws Exception {
        MDC.put("requestId", "req-denied-2");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", "pw", List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        accessDeniedHandler.handle(request, response, new AccessDeniedException("Access denied"));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));

        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("FORBIDDEN", body.get("code").asText());
        assertEquals("Access denied: insufficient-authority", body.get("message").asText());
        assertEquals("insufficient-authority", body.get("details").get("reason").asText());
        assertEquals("req-denied-2", body.get("requestId").asText());
        assertTrue(body.get("errorId").isNull());
    }
}
