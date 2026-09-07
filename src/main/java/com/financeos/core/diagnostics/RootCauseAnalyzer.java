package com.financeos.core.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.RequestSummary;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.RootCause;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.TimelineEntry;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RootCauseAnalyzer {

    private static final Pattern ORA_00001_PATTERN = Pattern.compile("unique constraint \\(([^)]+)\\) violated", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORA_COLUMN_PATTERN = Pattern.compile("cannot insert NULL into \\(([^)]+)\\)|integrity constraint \\(([^)]+)\\) violated", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_PATTERN = Pattern.compile("https?://([A-Za-z0-9.\\-_]+)|host\\s+([A-Za-z0-9.\\-_]+)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    public RootCauseAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RootCause analyze(
            List<TimelineEntry> timeline,
            RequestSummary requestSummary,
            int lookbackDays) {

        if (timeline == null || timeline.isEmpty()) {
            return notFound(lookbackDays);
        }

        // Rule 1: Look for ERROR request.failed line
        for (TimelineEntry entry : timeline) {
            if ("ERROR".equalsIgnoreCase(entry.level()) && "request.failed".equals(entry.event())) {
                return analyzeServerError(entry);
            }
        }

        // Also check any general ERROR line on server
        for (TimelineEntry entry : timeline) {
            if ("ERROR".equalsIgnoreCase(entry.level()) && "server".equals(entry.source())) {
                return analyzeServerError(entry);
            }
        }

        // Rule 2: Look for WARN request.failed line (4xx)
        for (TimelineEntry entry : timeline) {
            if ("WARN".equalsIgnoreCase(entry.level()) && "request.failed".equals(entry.event())) {
                return analyzeClientWarn(entry);
            }
        }

        // Also check any 4xx auth.denied or other WARN
        for (TimelineEntry entry : timeline) {
            if ("WARN".equalsIgnoreCase(entry.level()) && "server".equals(entry.source())) {
                return analyzeClientWarn(entry);
            }
        }

        // Rule 3: Access log present without request.failed
        if (requestSummary != null) {
            if (requestSummary.status() >= 500) {
                return new RootCause(
                        "SERVER_EXCEPTION",
                        "Server returned HTTP " + requestSummary.status(),
                        "no handler line — likely thrown outside the advice (filter/security)",
                        null,
                        null,
                        null,
                        List.of("Check servlet filter logs, security configuration, or container runtime logs.")
                );
            } else if (requestSummary.status() >= 200 && requestSummary.status() < 400) {
                return new RootCause(
                        "INCOMPLETE",
                        "Request succeeded on server (HTTP " + requestSummary.status() + ")",
                        "request succeeded on the server; check client lines",
                        null,
                        null,
                        null,
                        List.of("The server handled this request successfully. Check client-side logs or UI error handling.")
                );
            }
        }

        // Rule 4: Only client or Faro lines present
        boolean hasServerLines = timeline.stream().anyMatch(t -> "server".equals(t.source()));
        if (!hasServerLines) {
            return new RootCause(
                    "INCOMPLETE",
                    "Request never reached the server",
                    "never reached the server: offline, DNS, proxy, or the Vercel function failed before calling the API",
                    null,
                    null,
                    null,
                    List.of(
                            "Check client network connectivity and browser offline status.",
                            "Verify reverse proxy / gateway configuration.",
                            "Inspect Vercel serverless function logs."
                    )
            );
        }

        return new RootCause(
                "INCOMPLETE",
                "Log entries found without explicit failure marker",
                "Timeline contains entries but no request.failed event or abnormal status was identified.",
                null,
                null,
                null,
                List.of("Review the individual timeline events for subtle logic or state issues.")
        );
    }

    private RootCause analyzeServerError(TimelineEntry entry) {
        Map<String, Object> fields = entry.fields() != null ? entry.fields() : Collections.emptyMap();
        String exceptionClass = (String) fields.get("exceptionClass");
        if (exceptionClass != null && (exceptionClass.isBlank() || "null".equalsIgnoreCase(exceptionClass))) {
            exceptionClass = null;
        }
        String oraCode = (String) fields.get("oraCode");
        if (oraCode != null && (oraCode.isBlank() || "null".equalsIgnoreCase(oraCode))) {
            oraCode = null;
        }
        String stackTrace = entry.stackTrace();
        String rootFrame = extractRootFrame(stackTrace, entry.message());

        String kind = "SERVER_EXCEPTION";
        List<String> hints = new ArrayList<>();
        String detail = entry.message();

        // Evaluate known exception rules & hints
        String combined = (entry.message() + " " + (stackTrace != null ? stackTrace : "") + " " + (exceptionClass != null ? exceptionClass : "")).trim();

        if (oraCode != null || combined.contains("ORA-")) {
            if ("ORA-00001".equals(oraCode) || combined.contains("ORA-00001")) {
                Matcher m = ORA_00001_PATTERN.matcher(combined);
                String constraint = m.find() ? m.group(1) : "unique constraint";
                hints.add("unique constraint " + constraint + "; a duplicate write");
            } else if ("ORA-12839".equals(oraCode) || combined.contains("ORA-12839")) {
                hints.add("parallel DML on ADB; see V82 incident");
            } else if ("ORA-01400".equals(oraCode) || "ORA-02291".equals(oraCode) || combined.contains("ORA-01400") || combined.contains("ORA-02291")) {
                Matcher m = ORA_COLUMN_PATTERN.matcher(combined);
                String col = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : "column/table";
                hints.add("NOT NULL / FK violated: " + col);
            }
        }

        if (combined.contains("LazyInitializationException")) {
            hints.add("entity graph missing on the read path (see card.cardholder incident)");
        }
        if (combined.contains("OptimisticLockingFailureException")) {
            hints.add("concurrent update; retry");
        }
        if (combined.contains("DataIntegrityViolationException")) {
            hints.add("Database constraint violated. Check required fields and unique indexes.");
        }
        if (combined.contains("HttpMessageNotReadableException")) {
            hints.add("malformed body");
        }
        if (combined.contains("SocketTimeoutException") || combined.contains("ConnectException") || combined.contains("HttpTimeoutException")) {
            kind = "UPSTREAM_FAILURE";
            Matcher m = HOST_PATTERN.matcher(combined);
            String host = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : "upstream host";
            hints.add("Upstream failure connecting to " + host);
        }
        if (combined.contains("LlmException") || "LlmException".equals(exceptionClass)) {
            Object status = fields.get("httpStatus");
            Object retryAfter = fields.get("retryAfterMs");
            Object provider = fields.get("providerId");
            if (status != null && "429".equals(String.valueOf(status))) {
                kind = "RATE_LIMITED";
                hints.add("LLM rate limit exceeded for provider " + provider + (retryAfter != null ? " (retry after " + retryAfter + "ms)" : ""));
            } else {
                kind = "UPSTREAM_FAILURE";
                hints.add("LLM provider " + (provider != null ? provider : "upstream") + " failed with status " + status);
            }
        }

        String headline = (exceptionClass != null && !exceptionClass.isBlank())
                ? "Server Exception (" + simpleClassName(exceptionClass) + ")"
                : "Internal Server Error";

        if (oraCode != null && !oraCode.isBlank() && !"null".equalsIgnoreCase(oraCode)) {
            headline += " [" + oraCode + "]";
        }

        return new RootCause(
                kind,
                headline,
                detail != null && !detail.isBlank() ? detail : "An unhandled server exception occurred.",
                exceptionClass,
                oraCode,
                rootFrame,
                hints
        );
    }

    private RootCause analyzeClientWarn(TimelineEntry entry) {
        Map<String, Object> fields = entry.fields() != null ? entry.fields() : Collections.emptyMap();
        String code = fields.get("code") != null ? String.valueOf(fields.get("code")) : "CLIENT_ERROR";
        String message = entry.message();

        String kind;
        switch (code.toUpperCase()) {
            case "UNAUTHORIZED" -> kind = "UNAUTHENTICATED";
            case "FORBIDDEN" -> kind = "FORBIDDEN";
            case "RATE_LIMITED" -> kind = "RATE_LIMITED";
            default -> kind = "CLIENT_REJECTED";
        }

        String headline = code + ": " + (message != null ? message : "Client request rejected");
        String detail = "";
        Object detailsObj = fields.get("details");
        if (detailsObj instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            map.forEach((k, v) -> parts.add(k + "=" + v));
            detail = String.join(", ", parts);
        } else if (detailsObj != null) {
            detail = String.valueOf(detailsObj);
        } else {
            detail = message != null ? message : "";
        }

        List<String> hints = new ArrayList<>();
        if ("UNAUTHENTICATED".equals(kind)) {
            hints.add("User session was missing or expired. Authentication required.");
        } else if ("FORBIDDEN".equals(kind)) {
            hints.add("The authenticated user does not have permission for this resource.");
        } else if ("RATE_LIMITED".equals(kind)) {
            hints.add("Too many requests were sent in a short window. Back off and retry.");
        }

        return new RootCause(
                kind,
                headline,
                detail,
                null,
                null,
                null,
                hints
        );
    }

    public RootCause notFound(int lookbackDays) {
        return new RootCause(
                "NOT_FOUND_IN_LOGS",
                "No log entries found for this reference",
                "No log lines matching this ID were found in the logging backend.",
                null,
                null,
                null,
                List.of(
                        "The request occurred outside the log retention window (default " + lookbackDays + " days).",
                        "The reference ID was mistyped or incomplete.",
                        "Environment mismatch: local development server logs are not shipped to Loki.",
                        "A 4xx error occurred prior to client-tier logging deployment."
                )
        );
    }

    private String extractRootFrame(String stackTrace, String fallbackMessage) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return fallbackMessage;
        }
        String[] lines = stackTrace.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("at ") && !trimmed.startsWith("...") && !trimmed.startsWith("Suppressed:")) {
                return trimmed;
            }
        }
        return lines.length > 0 ? lines[0].trim() : fallbackMessage;
    }

    private String simpleClassName(String fullClass) {
        int idx = fullClass.lastIndexOf('.');
        return idx >= 0 ? fullClass.substring(idx + 1) : fullClass;
    }
}
