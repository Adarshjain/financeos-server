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

@Component
@Profile("e2e")
@Order(5)
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
            if (pattern != null && !pattern.isBlank() && !shouldSkip(pattern)) {
                registry.record(request.getMethod(), pattern, response.getStatus());
            }
        }
    }

    private boolean shouldSkip(String pattern) {
        return pattern.startsWith("/api/e2e/")
                || pattern.equals("/api/e2e")
                || pattern.startsWith("/actuator/")
                || pattern.equals("/actuator")
                || pattern.startsWith("/v3/api-docs");
    }
}
