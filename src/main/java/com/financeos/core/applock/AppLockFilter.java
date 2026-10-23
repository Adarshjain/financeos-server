package com.financeos.core.applock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.GlobalExceptionHandler;
import com.financeos.core.observability.Events;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses every API request of a locked, app-lock-armed session with 423 {@code APP_LOCKED}.
 * Unauthenticated requests, OPTIONS and unarmed sessions pass through untouched (an unarmed session
 * costs one session-attribute read, no DB query).
 *
 * <p>Open while locked: sign-out and sign-in routes, and the routes the lock screen itself needs.
 * {@code /auth/me} and {@code /app-lock/heartbeat} are deliberately NOT open.
 */
@Component
public class AppLockFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AppLockFilter.class);

    private static final String BASE = "/api/v1/app-lock";
    private static final Set<String> OPEN_PATHS = Set.of(
            "/api/v1/auth/logout",
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            BASE + "/status",
            BASE + "/unlock/options",
            BASE + "/unlock/verify",
            BASE + "/lock");
    private static final String OPEN_PREFIX = "/api/v1/auth/google/";

    private final AppLockService appLock;
    private final ObjectMapper objectMapper;

    public AppLockFilter(AppLockService appLock, ObjectMapper objectMapper) {
        this.appLock = appLock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (shouldDeny(request)) {
            deny(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean shouldDeny(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !isAuthenticated()) {
            return false;
        }
        HttpSession session = request.getSession(false);
        if (session == null || !appLock.isArmed(session)) {
            return false;
        }
        String path = request.getRequestURI();
        if (isOpen(path)) {
            return false;
        }
        return appLock.isLocked(session);
    }

    static boolean isOpen(String path) {
        return OPEN_PATHS.contains(path) || path.startsWith(OPEN_PREFIX);
    }

    private static boolean isAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    private void deny(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestId = MDC.get("requestId");
        log.warn("App lock denied request: path={}", request.getRequestURI(),
                StructuredArguments.keyValue("event", Events.APP_LOCK_DENIED),
                StructuredArguments.keyValue("path", request.getRequestURI()),
                StructuredArguments.keyValue("requestId", requestId));
        response.setStatus(HttpStatus.LOCKED.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), new GlobalExceptionHandler.ErrorResponse(
                "APP_LOCKED", "FinanceOS is locked", Map.of(), Instant.now(appLock.clock()), null, requestId));
    }
}
