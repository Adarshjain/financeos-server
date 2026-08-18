package com.financeos.core.observability;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * Custom AccessDeniedHandler logging event=auth.denied at WARN level on 403 Forbidden responses.
 */
@Slf4j
@Component
public class LoggingAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {

        String routeAttr = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = (routeAttr != null && !routeAttr.isBlank()) ? routeAttr : "UNMATCHED";
        String requestId = MDC.get("requestId");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String reason = (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName()))
                ? "unauthenticated"
                : "insufficient-authority";

        log.warn("Access denied (403): route={}, reason={}", route, reason,
                StructuredArguments.keyValue("event", Events.AUTH_DENIED),
                StructuredArguments.keyValue("route", route),
                StructuredArguments.keyValue("requestId", requestId),
                StructuredArguments.keyValue("reason", reason)
        );

        response.setStatus(HttpStatus.FORBIDDEN.value());
    }
}
