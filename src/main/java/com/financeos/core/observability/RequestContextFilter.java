package com.financeos.core.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Outermost filter (HIGHEST_PRECEDENCE) that initializes or validates the X-Request-Id header.
 * Ensures requestId is present in MDC for all subsequent filters (including security) and echoes
 * it back in the response header.
 */
@Component("financeOsRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID_KEY = "requestId";
    public static final String SESSION_ID_HEADER = "X-Session-Id";
    public static final String MDC_SESSION_ID_KEY = "sessionId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (!isValidId(requestId)) {
            requestId = generateRequestId();
        }

        String sessionId = request.getHeader(SESSION_ID_HEADER);
        boolean hasValidSessionId = isValidId(sessionId);

        MDC.put(MDC_REQUEST_ID_KEY, requestId);
        if (hasValidSessionId) {
            MDC.put(MDC_SESSION_ID_KEY, sessionId);
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID_KEY);
            if (hasValidSessionId) {
                MDC.remove(MDC_SESSION_ID_KEY);
            }
        }
    }

    private boolean isValidId(String id) {
        if (id == null || id.isBlank() || id.length() > 64) {
            return false;
        }
        return id.matches("^[A-Za-z0-9\\-_]+$");
    }

    private String generateRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
