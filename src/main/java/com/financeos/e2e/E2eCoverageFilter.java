package com.financeos.e2e;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Set;

@Component
@Profile("e2e")
// Ahead of the Spring Security chain (order -100): endpoints Security answers itself — logout — never
// let the chain continue, so a filter behind it would never run for them. The best-matching pattern
// is read after the chain returns, so running first changes nothing for dispatcher-handled requests.
@Order(-200)
public class E2eCoverageFilter extends OncePerRequestFilter {

    private final CoverageRegistry registry;

    public E2eCoverageFilter(CoverageRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern == null || pattern.isBlank()) {
                pattern = filterAnsweredPattern(request);
            }
            if (pattern != null && !pattern.isBlank() && !shouldSkip(pattern)) {
                registry.record(request.getMethod(), pattern, response.getStatus());
            }
        }
    }

    /**
     * Endpoints that Spring Security answers inside the filter chain never reach the DispatcherServlet,
     * so no best-matching pattern is set. They are still part of the OpenAPI contract and must count.
     */
    static final Set<String> FILTER_ANSWERED_PATHS = Set.of("/api/v1/auth/logout");

    private static String filterAnsweredPattern(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return FILTER_ANSWERED_PATHS.contains(path) ? path : null;
    }

    private boolean shouldSkip(String pattern) {
        return pattern.startsWith("/api/e2e/")
                || pattern.equals("/api/e2e")
                || pattern.startsWith("/actuator/")
                || pattern.equals("/actuator")
                || pattern.startsWith("/v3/api-docs");
    }
}
