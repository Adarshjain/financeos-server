package com.financeos.core.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextFilterSessionIdTest {

    private RequestContextFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new RequestContextFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        MDC.clear();
    }

    @Test
    void testValidSessionIdPresentInMdcInsideChainAndAbsentAfter() throws Exception {
        String validSessionId = "session-123_abc-456";
        request.addHeader(RequestContextFilter.SESSION_ID_HEADER, validSessionId);

        FilterChain chain = (req, res) -> {
            assertEquals(validSessionId, MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
            assertNull(response.getHeader(RequestContextFilter.SESSION_ID_HEADER),
                    "X-Session-Id must not be echoed in the response header");
        };

        filter.doFilter(request, response, chain);
        assertNull(MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY),
                "MDC sessionId must be removed after filter chain");
    }

    @Test
    void testInvalidSessionIdAbsentInsideChain() throws Exception {
        // 65 chars
        String oversized = "a".repeat(65);
        request.addHeader(RequestContextFilter.SESSION_ID_HEADER, oversized);

        filter.doFilter(request, response, (req, res) -> {
            assertNull(MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
        });

        // bad characters like $
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.addHeader(RequestContextFilter.SESSION_ID_HEADER, "bad$id");
        filter.doFilter(request, response, (req, res) -> {
            assertNull(MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
        });

        // blank
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        request.addHeader(RequestContextFilter.SESSION_ID_HEADER, "   ");
        filter.doFilter(request, response, (req, res) -> {
            assertNull(MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
        });
    }

    @Test
    void testMissingSessionIdAbsentInsideChain() throws Exception {
        filter.doFilter(request, response, (req, res) -> {
            assertNull(MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
        });
        assertNull(MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
    }

    @Test
    void testSessionIdNotEchoedAsResponseHeader() throws Exception {
        request.addHeader(RequestContextFilter.SESSION_ID_HEADER, "valid-session-123");
        filter.doFilter(request, response, (req, res) -> {});
        assertNull(response.getHeader(RequestContextFilter.SESSION_ID_HEADER));
        assertNotNull(response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
    }

    @Test
    void testBothHeadersPresentPreservesRequestIdBehavior() throws Exception {
        String validReqId = "custom-req-id-123";
        String validSessionId = "custom-session-id-456";
        request.addHeader(RequestContextFilter.REQUEST_ID_HEADER, validReqId);
        request.addHeader(RequestContextFilter.SESSION_ID_HEADER, validSessionId);

        filter.doFilter(request, response, (req, res) -> {
            assertEquals(validReqId, MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY));
            assertEquals(validSessionId, MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
            assertEquals(validReqId, response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
            assertNull(response.getHeader(RequestContextFilter.SESSION_ID_HEADER));
        });

        assertNull(MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY));
        assertNull(MDC.get(RequestContextFilter.MDC_SESSION_ID_KEY));
    }
}
