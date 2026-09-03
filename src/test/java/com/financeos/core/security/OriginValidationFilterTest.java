package com.financeos.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;

import static org.mockito.Mockito.*;

class OriginValidationFilterTest {

    private AppConfigProperties appConfig;
    private OriginValidationFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        appConfig = new AppConfigProperties();
        appConfig.getCors().setAllowedOrigins("https://localhost:6970, https://financeos.duckdns.org");
        filter = new OriginValidationFilter(appConfig);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        filterChain = mock(FilterChain.class);
    }

    @Test
    void allowsSafeMethodsRegardlessOfOrigin() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeader("Origin")).thenReturn("https://evil.com");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    void allowsUnsafeMethodWithoutOriginHeader() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Origin")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    void allowsUnsafeMethodWithAllowedOrigin() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Origin")).thenReturn("https://financeos.duckdns.org");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).sendError(anyInt(), anyString());
    }

    @Test
    void rejectsUnsafeMethodWithDisallowedOrigin() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("POST");
        when(request.getHeader("Origin")).thenReturn("https://evil.com");
        when(request.getRequestURI()).thenReturn("/api/v1/transactions");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
    }

    @Test
    void rejectsDeleteWithDisallowedOrigin() throws ServletException, IOException {
        when(request.getMethod()).thenReturn("DELETE");
        when(request.getHeader("Origin")).thenReturn("http://malicious.org");
        when(request.getRequestURI()).thenReturn("/api/v1/accounts/123");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
    }
}
