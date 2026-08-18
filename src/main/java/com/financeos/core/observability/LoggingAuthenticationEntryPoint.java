package com.financeos.core.observability;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Arrays;

/**
 * Custom AuthenticationEntryPoint logging event=auth.denied at WARN level on 401 Unauthorized responses.
 */
@Slf4j
@Component
public class LoggingAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {

        String routeAttr = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = (routeAttr != null && !routeAttr.isBlank()) ? routeAttr : "UNMATCHED";
        String requestId = MDC.get("requestId");

        String reason = determineReason(request);

        log.warn("Authentication denied (401): route={}, reason={}", route, reason,
                StructuredArguments.keyValue("event", Events.AUTH_DENIED),
                StructuredArguments.keyValue("route", route),
                StructuredArguments.keyValue("requestId", requestId),
                StructuredArguments.keyValue("reason", reason)
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    private String determineReason(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null || Arrays.stream(cookies).noneMatch(c -> "FINANCEOS_SESSION".equals(c.getName()))) {
            return "no-session-cookie";
        }
        return "session-expired";
    }
}
