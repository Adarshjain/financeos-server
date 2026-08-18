package com.financeos.core.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Collections;

class AccessLogFilterTest {

    private AccessLogFilter accessLogFilter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private Logger logger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        accessLogFilter = new AccessLogFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        logger = (Logger) LoggerFactory.getLogger("com.financeos.core.observability.AccessLog");
        logger.setLevel(Level.DEBUG);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(listAppender);
        MDC.clear();
    }

    @Test
    void testUserIdPresentInArgumentArrayForAuthenticatedRequest() throws Exception {
        MDC.put("userId", "42");
        MDC.put("requestId", "req-123");

        request.setMethod("GET");
        request.setRequestURI("/api/v1/accounts");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/accounts");
        response.setStatus(200);

        FilterChain chain = (req, res) -> {};

        accessLogFilter.doFilter(request, response, chain);

        assertEquals(1, listAppender.list.size());
        ILoggingEvent event = listAppender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());

        Object[] args = event.getArgumentArray();
        assertNotNull(args);
        boolean foundUserIdArg = Arrays.stream(args)
                .anyMatch(arg -> arg != null && arg.toString().contains("userId=42"));
        assertTrue(foundUserIdArg, "AccessLog event argument array must contain StructuredArgument for userId=42");
    }

    @Test
    void testRouteTemplatedAndUnmatchedFallback() throws Exception {
        // Test 1: Templated route
        request.setMethod("GET");
        request.setRequestURI("/api/v1/transactions/999");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/transactions/{id}");
        response.setStatus(200);

        accessLogFilter.doFilter(request, response, (req, res) -> {});

        assertEquals(1, listAppender.list.size());
        assertTrue(listAppender.list.get(0).getFormattedMessage().contains("/api/v1/transactions/{id}"));

        // Test 2: Unmatched route fallback
        listAppender.list.clear();
        MockHttpServletRequest request2 = new MockHttpServletRequest("GET", "/nonexistent/path");
        MockHttpServletResponse response2 = new MockHttpServletResponse();
        response2.setStatus(404);

        accessLogFilter.doFilter(request2, response2, (req, res) -> {});

        assertEquals(1, listAppender.list.size());
        assertTrue(listAppender.list.get(0).getFormattedMessage().contains("UNMATCHED"));
    }

    @Test
    void testSlowRequestLoggedAsWarnWithoutSleeping() throws Exception {
        accessLogFilter.setSlowThresholdMs(-1L); // Force slow=true instantly without sleeping

        request.setMethod("POST");
        request.setRequestURI("/api/v1/reports/calculate");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/reports/calculate");
        response.setStatus(200);

        accessLogFilter.doFilter(request, response, (req, res) -> {});

        assertEquals(1, listAppender.list.size());
        ILoggingEvent event = listAppender.list.get(0);
        assertEquals(Level.WARN, event.getLevel());
    }

    @Test
    void testActuatorHealthLoggedAtDebug() throws Exception {
        request.setMethod("GET");
        request.setRequestURI("/actuator/health");
        response.setStatus(200);

        accessLogFilter.doFilter(request, response, (req, res) -> {});

        assertEquals(1, listAppender.list.size());
        ILoggingEvent event = listAppender.list.get(0);
        assertEquals(Level.DEBUG, event.getLevel());
    }

    @SpringBootTest
    @AutoConfigureMockMvc
    static class AccessLogFilterIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private UserRepository userRepository;

        @Test
        void testFilterOrderingPropagatesUserIdToAccessLog() throws Exception {
            User user = new User();
            user.setDisplayName("Filter Test User");
            user.setEmail("filtertest@financeos.com");
            user.setPasswordHash("hash");
            user = userRepository.save(user);

            Logger accessLogger = (Logger) LoggerFactory.getLogger("com.financeos.core.observability.AccessLog");
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            accessLogger.addAppender(appender);

            try {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user.getEmail(), "pw", Collections.emptyList()));

                mockMvc.perform(get("/api/v1/accounts"))
                        .andExpect(status().isOk());

                assertFalse(appender.list.isEmpty());
                ILoggingEvent event = appender.list.get(appender.list.size() - 1);
                assertNotNull(event.getArgumentArray());
                String expectedUserIdStr = "userId=" + user.getId();
                boolean foundUserId = Arrays.stream(event.getArgumentArray())
                        .anyMatch(arg -> arg != null && arg.toString().contains(expectedUserIdStr));
                assertTrue(foundUserId, "AccessLog event argument array must contain StructuredArgument for " + expectedUserIdStr);
            } finally {
                SecurityContextHolder.clearContext();
                accessLogger.detachAppender(appender);
            }
        }
    }
}
