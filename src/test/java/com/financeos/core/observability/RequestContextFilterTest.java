package com.financeos.core.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class RequestContextFilterTest {

    private RequestContextFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new RequestContextFilter();
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void testValidInboundRequestIdHeaderAccepted() throws Exception {
        String validId = "custom-req-id-12345";
        request.addHeader(RequestContextFilter.REQUEST_ID_HEADER, validId);

        FilterChain chain = (req, res) -> {
            assertEquals(validId, MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY));
            assertEquals(validId, response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
        };

        filter.doFilter(request, response, chain);
        assertNull(MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY));
    }

    @Test
    void testMissingRequestIdHeaderGenerated() throws Exception {
        FilterChain chain = (req, res) -> {
            String mdcReqId = MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY);
            assertNotNull(mdcReqId);
            assertEquals(20, mdcReqId.length());
            assertEquals(mdcReqId, response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
        };

        filter.doFilter(request, response, chain);
        assertNull(MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY));
    }

    @Test
    void testOversizedRequestIdHeaderReplaced() throws Exception {
        String oversizedId = "a".repeat(70);
        request.addHeader(RequestContextFilter.REQUEST_ID_HEADER, oversizedId);

        FilterChain chain = (req, res) -> {
            String mdcReqId = MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY);
            assertNotNull(mdcReqId);
            assertNotEquals(oversizedId, mdcReqId);
            assertEquals(20, mdcReqId.length());
            assertEquals(mdcReqId, response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
        };

        filter.doFilter(request, response, chain);
    }

    @Test
    void testMalformedRequestIdHeaderReplaced() throws Exception {
        String malformedId = "req-id-with-special-chars!@#$%^&*()";
        request.addHeader(RequestContextFilter.REQUEST_ID_HEADER, malformedId);

        FilterChain chain = (req, res) -> {
            String mdcReqId = MDC.get(RequestContextFilter.MDC_REQUEST_ID_KEY);
            assertNotNull(mdcReqId);
            assertNotEquals(malformedId, mdcReqId);
            assertEquals(20, mdcReqId.length());
            assertEquals(mdcReqId, response.getHeader(RequestContextFilter.REQUEST_ID_HEADER));
        };

        filter.doFilter(request, response, chain);
    }
}
