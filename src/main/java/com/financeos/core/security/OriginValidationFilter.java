package com.financeos.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Defense-in-depth origin check filter.
 * Rejects non-GET/HEAD/OPTIONS/TRACE requests carrying an Origin header
 * unless that origin is listed in CORS_ORIGINS.
 * Requests without an Origin header (such as server actions or curl) are unaffected.
 */
@Component
public class OriginValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginValidationFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AppConfigProperties appConfig;

    public OriginValidationFilter(AppConfigProperties appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String method = request.getMethod();
        if (method != null && !SAFE_METHODS.contains(method.toUpperCase())) {
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isBlank()) {
                Set<String> allowedOrigins = parseAllowedOrigins();
                if (!allowedOrigins.contains(origin.trim())) {
                    log.warn("Origin check rejected: origin={}, method={}, path={}, allowedOrigins={}",
                            origin, method, request.getRequestURI(), allowedOrigins);
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private Set<String> parseAllowedOrigins() {
        String allowed = appConfig.getCors() != null ? appConfig.getCors().getAllowedOrigins() : null;
        if (allowed == null || allowed.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
