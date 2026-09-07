package com.financeos.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.RequestSummary;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.RootCause;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse.TimelineEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RootCauseAnalyzerTest {

    private RootCauseAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new RootCauseAnalyzer(new ObjectMapper());
    }

    private TimelineEntry createEntry(String source, String level, String event, String message,
                                      Map<String, Object> fields, String stackTrace) {
        return new TimelineEntry(
                Instant.now(),
                source,
                level,
                event,
                "com.financeos.TestLogger",
                message,
                fields != null ? fields : Collections.emptyMap(),
                stackTrace
        );
    }

    @Test
    void testEmptyOrNullTimelineReturnsNotFoundWithLookbackDays() {
        RootCause rc1 = analyzer.analyze(null, null, 14);
        assertEquals("NOT_FOUND_IN_LOGS", rc1.kind());
        assertTrue(rc1.hints().stream().anyMatch(h -> h.contains("14 days")));

        RootCause rc2 = analyzer.analyze(List.of(), null, 7);
        assertEquals("NOT_FOUND_IN_LOGS", rc2.kind());
        assertTrue(rc2.hints().stream().anyMatch(h -> h.contains("7 days")));
    }

    @Test
    void testServerErrorOra00001ConstraintRegex() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "unique constraint (FINANCEOS.UK_ACC_NAME) violated",
                Map.of("oraCode", "ORA-00001", "exceptionClass", "org.hibernate.exception.ConstraintViolationException"),
                "org.hibernate.exception.ConstraintViolationException: ORA-00001\n\tat org.hibernate.Engine.run(Engine.java:12)"
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertEquals("Server Exception (ConstraintViolationException) [ORA-00001]", rc.headline());
        assertEquals("ORA-00001", rc.oraCode());
        assertEquals("org.hibernate.exception.ConstraintViolationException", rc.exceptionClass());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("FINANCEOS.UK_ACC_NAME")));
    }

    @Test
    void testServerErrorOra12839ParallelDml() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "ORA-12839: cannot modify an object in parallel after modifying it",
                Map.of("oraCode", "ORA-12839", "exceptionClass", "java.sql.SQLException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertEquals("ORA-12839", rc.oraCode());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("parallel DML on ADB")));
    }

    @Test
    void testServerErrorOra01400CannotInsertNullColumn() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "cannot insert NULL into (FINANCEOS.USERS.EMAIL)",
                Map.of("oraCode", "ORA-01400", "exceptionClass", "java.sql.SQLException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("FINANCEOS.USERS.EMAIL")));
    }

    @Test
    void testServerErrorOra02291FkViolation() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "integrity constraint (FINANCEOS.FK_ACC_USER) violated",
                Map.of("oraCode", "ORA-02291", "exceptionClass", "java.sql.SQLException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("FINANCEOS.FK_ACC_USER")));
    }

    @Test
    void testServerErrorLazyInitializationException() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "could not initialize proxy - no Session",
                Map.of("exceptionClass", "org.hibernate.LazyInitializationException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("entity graph missing")));
    }

    @Test
    void testServerErrorOptimisticLockingFailureException() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "Row was updated or deleted by another transaction",
                Map.of("exceptionClass", "org.springframework.dao.OptimisticLockingFailureException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("concurrent update; retry")));
    }

    @Test
    void testServerErrorDataIntegrityViolationException() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "could not execute statement",
                Map.of("exceptionClass", "org.springframework.dao.DataIntegrityViolationException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("Database constraint violated")));
    }

    @Test
    void testServerErrorHttpMessageNotReadableException() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "JSON parse error",
                Map.of("exceptionClass", "org.springframework.http.converter.HttpMessageNotReadableException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("malformed body")));
    }

    @Test
    void testServerErrorSocketTimeoutBecomesUpstreamFailureWithHost() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "ConnectException: Failed to connect to https://api.openai.com/v1",
                Map.of("exceptionClass", "java.net.ConnectException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("UPSTREAM_FAILURE", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("api.openai.com")));
    }

    @Test
    void testServerErrorLlmException429BecomesRateLimited() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "LLM rate limit reached",
                Map.of("exceptionClass", "com.financeos.llm.LlmException", "httpStatus", 429, "providerId", "gemini", "retryAfterMs", 5000),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("RATE_LIMITED", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("gemini") && h.contains("5000ms")));
    }

    @Test
    void testServerErrorLlmException500BecomesUpstreamFailure() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "LLM provider internal error",
                Map.of("exceptionClass", "com.financeos.llm.LlmException", "httpStatus", 500, "providerId", "groq"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("UPSTREAM_FAILURE", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("groq") && h.contains("500")));
    }

    @Test
    void testServerErrorOraCodeNullStringTreatedAbsent() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "Generic failure",
                Map.of("oraCode", "null", "exceptionClass", "java.lang.IllegalStateException"),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertNull(rc.oraCode());
        assertEquals("Server Exception (IllegalStateException)", rc.headline());
    }

    @Test
    void testServerErrorWithoutExceptionClassGivesInternalServerErrorHeadline() {
        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "Uncaught system error",
                Map.of("exceptionClass", "   "),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertNull(rc.exceptionClass());
        assertEquals("Internal Server Error", rc.headline());
    }

    @Test
    void testClientWarnUnauthorizedBecomesUnauthenticated() {
        TimelineEntry entry = createEntry(
                "server", "WARN", "request.failed",
                "Authentication required",
                Map.of("code", "UNAUTHORIZED", "details", Map.of("reason", "no-session-cookie")),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("UNAUTHENTICATED", rc.kind());
        assertEquals("reason=no-session-cookie", rc.detail());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("Authentication required")));
    }

    @Test
    void testClientWarnForbidden() {
        TimelineEntry entry = createEntry(
                "server", "WARN", "request.failed",
                "Access denied",
                Map.of("code", "FORBIDDEN", "details", Map.of("reason", "insufficient-authority")),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("FORBIDDEN", rc.kind());
        assertEquals("reason=insufficient-authority", rc.detail());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("permission for this resource")));
    }

    @Test
    void testClientWarnRateLimited() {
        TimelineEntry entry = createEntry(
                "server", "WARN", "request.failed",
                "Too many requests",
                Map.of("code", "RATE_LIMITED", "details", Map.of("retryAfterSeconds", "30")),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("RATE_LIMITED", rc.kind());
        assertTrue(rc.hints().stream().anyMatch(h -> h.contains("Back off and retry")));
    }

    @Test
    void testClientWarnClientRejectedWithMultipleDetails() {
        TimelineEntry entry = createEntry(
                "server", "WARN", "request.failed",
                "Validation failed",
                Map.of("code", "VALIDATION_ERROR", "details", Map.of("field1", "bad", "field2", "missing")),
                null
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("CLIENT_REJECTED", rc.kind());
        assertTrue(rc.detail().contains("field1=bad"));
        assertTrue(rc.detail().contains("field2=missing"));
    }

    @Test
    void testAccessLog5xxWithoutRequestFailed() {
        TimelineEntry entry = createEntry("server", "INFO", "http.request", "GET /api/v1/accounts", Collections.emptyMap(), null);
        RequestSummary req = new RequestSummary("GET", "/api/v1/accounts", 502, 100L, Instant.now(), "user-1", null, "1.0", "UA", false);

        RootCause rc = analyzer.analyze(List.of(entry), req, 14);
        assertEquals("SERVER_EXCEPTION", rc.kind());
        assertTrue(rc.detail().contains("outside the advice"));
    }

    @Test
    void testAccessLog2xxWithoutRequestFailed() {
        TimelineEntry entry = createEntry("server", "INFO", "http.request", "GET /api/v1/accounts", Collections.emptyMap(), null);
        RequestSummary req = new RequestSummary("GET", "/api/v1/accounts", 200, 20L, Instant.now(), "user-1", null, "1.0", "UA", false);

        RootCause rc = analyzer.analyze(List.of(entry), req, 14);
        assertEquals("INCOMPLETE", rc.kind());
        assertTrue(rc.detail().contains("request succeeded on the server"));
    }

    @Test
    void testOnlyClientLinesPresentIndicatesNeverReachedServer() {
        TimelineEntry entry = createEntry("client", "INFO", null, "Client navigation started", Collections.emptyMap(), null);

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("INCOMPLETE", rc.kind());
        assertTrue(rc.detail().contains("never reached the server"));
    }

    @Test
    void testFallbackForUnclassifiedServerLines() {
        TimelineEntry entry = createEntry("server", "INFO", "some.event", "Server background task ran", Collections.emptyMap(), null);

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("INCOMPLETE", rc.kind());
        assertTrue(rc.detail().contains("Timeline contains entries but no request.failed"));
    }

    @Test
    void testRootFrameSkipsAtAndSuppressedAndFallsBackToMessage() {
        String stack = """
                \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1062)
                \t... 50 more
                \tSuppressed: java.lang.Exception
                com.financeos.domain.AccountService.createAccount(AccountService.java:45)
                \tat com.financeos.api.AccountController.create(AccountController.java:20)
                """;

        TimelineEntry entry = createEntry(
                "server", "ERROR", "request.failed",
                "Fallback message",
                Map.of("exceptionClass", "java.lang.RuntimeException"),
                stack
        );

        RootCause rc = analyzer.analyze(List.of(entry), null, 14);
        assertEquals("com.financeos.domain.AccountService.createAccount(AccountService.java:45)", rc.rootFrame());

        // Fallback when stack is null or blank
        TimelineEntry noStackEntry = createEntry(
                "server", "ERROR", "request.failed",
                "Explicit fallback message",
                Map.of("exceptionClass", "java.lang.RuntimeException"),
                "   "
        );
        RootCause rcNoStack = analyzer.analyze(List.of(noStackEntry), null, 14);
        assertEquals("Explicit fallback message", rcNoStack.rootFrame());
    }
}
