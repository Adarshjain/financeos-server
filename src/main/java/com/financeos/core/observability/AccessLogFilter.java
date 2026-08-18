package com.financeos.core.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Innermost filter (@Order(10)) that logs a single structured access log entry per request
 * at request completion. Executed inside UserContextFilter so MDC userId is accessible.
 */
@Component
@Order(10)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger accessLog = LoggerFactory.getLogger("com.financeos.core.observability.AccessLog");

    public static final String ERROR_CODE_ATTR = "com.financeos.observability.errorCode";
    public static final String ERROR_ID_ATTR = "com.financeos.observability.errorId";

    private long slowThresholdMs = 1000L;

    void setSlowThresholdMs(long slowThresholdMs) {
        this.slowThresholdMs = slowThresholdMs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - startTime;
            logRequest(request, response, durationMs);
        }
    }

    private void logRequest(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        String method = request.getMethod();

        String routeAttr = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = (routeAttr != null && !routeAttr.isBlank()) ? routeAttr : "UNMATCHED";

        int status = response.getStatus();

        String userId = MDC.get("userId");
        String requestId = MDC.get("requestId");

        long reqBytes = request.getContentLengthLong();
        if (reqBytes < 0) {
            reqBytes = 0;
        }

        String clientIp = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 120) {
            userAgent = userAgent.substring(0, 120);
        }

        String errorCode = (String) request.getAttribute(ERROR_CODE_ATTR);
        String errorId = (String) request.getAttribute(ERROR_ID_ATTR);

        boolean slow = durationMs > slowThresholdMs;

        String uri = request.getRequestURI();
        boolean isHealthCheck = "/actuator/health".equals(uri);

        String logMessage = "HTTP {} {} -> {} in {}ms";

        if (isHealthCheck) {
            if (accessLog.isDebugEnabled()) {
                accessLog.debug(logMessage, buildArgs(method, route, status, durationMs, userId, requestId, reqBytes, clientIp, userAgent, errorCode, errorId, slow));
            }
        } else if (slow) {
            if (accessLog.isWarnEnabled()) {
                accessLog.warn(logMessage, buildArgs(method, route, status, durationMs, userId, requestId, reqBytes, clientIp, userAgent, errorCode, errorId, slow));
            }
        } else {
            if (accessLog.isInfoEnabled()) {
                accessLog.info(logMessage, buildArgs(method, route, status, durationMs, userId, requestId, reqBytes, clientIp, userAgent, errorCode, errorId, slow));
            }
        }
    }

    private Object[] buildArgs(String method, String route, int status, long durationMs,
                               String userId, String requestId, long reqBytes,
                               String clientIp, String userAgent, String errorCode,
                               String errorId, boolean slow) {
        return new Object[] {
                method, route, status, durationMs,
                StructuredArguments.keyValue("event", Events.HTTP_REQUEST),
                StructuredArguments.keyValue("method", method),
                StructuredArguments.keyValue("route", route),
                StructuredArguments.keyValue("status", status),
                StructuredArguments.keyValue("durationMs", durationMs),
                StructuredArguments.keyValue("userId", userId),
                StructuredArguments.keyValue("requestId", requestId),
                StructuredArguments.keyValue("reqBytes", reqBytes),
                StructuredArguments.keyValue("clientIp", clientIp),
                StructuredArguments.keyValue("userAgent", userAgent),
                StructuredArguments.keyValue("errorCode", errorCode),
                StructuredArguments.keyValue("errorId", errorId),
                StructuredArguments.keyValue("slow", slow)
        };
    }

    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            int commaIndex = xForwardedFor.indexOf(',');
            return (commaIndex > 0 ? xForwardedFor.substring(0, commaIndex) : xForwardedFor).trim();
        }
        return request.getRemoteAddr();
    }
}
