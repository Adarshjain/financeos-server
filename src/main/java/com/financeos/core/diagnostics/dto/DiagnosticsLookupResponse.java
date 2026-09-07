package com.financeos.core.diagnostics.dto;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DiagnosticsLookupResponse(
        String ref,
        String refType,
        @Nullable String requestId,
        @Nullable String errorId,
        boolean found,
        boolean truncated,
        @Nullable RequestSummary request,
        RootCause rootCause,
        List<TimelineEntry> timeline,
        boolean rawAvailable) {

    public record RequestSummary(
            String method,
            String route,
            int status,
            long durationMs,
            Instant at,
            @Nullable String userId,
            @Nullable String userEmail,
            @Nullable String version,
            @Nullable String userAgent,
            boolean slow) {}

    public record RootCause(
            String kind,
            String headline,
            String detail,
            @Nullable String exceptionClass,
            @Nullable String oraCode,
            @Nullable String rootFrame,
            List<String> hints) {}

    public record TimelineEntry(
            Instant at,
            String source,
            String level,
            @Nullable String event,
            @Nullable String logger,
            String message,
            Map<String, Object> fields,
            @Nullable String stackTrace) {}
}
