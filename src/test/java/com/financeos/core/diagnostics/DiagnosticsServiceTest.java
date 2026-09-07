package com.financeos.core.diagnostics;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.core.diagnostics.dto.DiagnosticsLookupResponse;
import com.financeos.core.diagnostics.dto.RawLogEntry;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.AppConfigProperties;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiagnosticsServiceTest {

    @Mock
    private LokiQueryClient lokiClient;

    @Mock
    private UserRepository userRepository;

    private AppConfigProperties appConfig;
    private ObjectMapper objectMapper;
    private RootCauseAnalyzer rootCauseAnalyzer;
    private DiagnosticsService diagnosticsService;

    private Logger diagnosticsLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        appConfig = new AppConfigProperties();
        appConfig.getDiagnostics().getLoki().setLookbackDays(14);
        appConfig.getDiagnostics().getLoki().setMaxLines(100);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        rootCauseAnalyzer = new RootCauseAnalyzer(objectMapper);
        diagnosticsService = new DiagnosticsService(appConfig, lokiClient, rootCauseAnalyzer, userRepository, objectMapper);

        diagnosticsLogger = (Logger) LoggerFactory.getLogger(DiagnosticsService.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        diagnosticsLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        diagnosticsLogger.detachAppender(listAppender);
    }

    @Test
    void testRefValidationDelegatedFirst() {
        doThrow(new ValidationException("Invalid ref format"))
                .when(lokiClient).validateRef("bad$ref");

        UUID adminId = UUID.randomUUID();
        assertThrows(ValidationException.class,
                () -> diagnosticsService.lookup(adminId, "bad$ref", "auto", null, null));
        verify(lokiClient).validateRef("bad$ref");
        verifyNoMoreInteractions(lokiClient);
    }

    @Test
    void testTypeAutoDetectAndExplicitOverride() {
        UUID adminId = UUID.randomUUID();

        // 8 Crockford chars -> errorId
        when(lokiClient.query(anyString(), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse res1 = diagnosticsService.lookup(adminId, "hm6hk5g6", "auto", null, null);
        assertEquals("errorId", res1.refType());
        verify(lokiClient).query(eq("{service=\"financeos-server\"} | json | errorId=\"HM6HK5G6\""), any(), any(), eq(100));

        // 20-char requestId -> requestId
        String reqId = "d64d860242054feab432";
        DiagnosticsLookupResponse res2 = diagnosticsService.lookup(adminId, reqId, "auto", null, null);
        assertEquals("requestId", res2.refType());
        verify(lokiClient).query(eq("{service=\"financeos-server\"} | json | requestId=\"" + reqId + "\""), any(), any(), eq(100));

        // Explicit type overrides auto-detection
        DiagnosticsLookupResponse res3 = diagnosticsService.lookup(adminId, "HM6HK5G6", "requestId", null, null);
        assertEquals("requestId", res3.refType());
        verify(lokiClient).query(eq("{service=\"financeos-server\"} | json | requestId=\"HM6HK5G6\""), any(), any(), eq(100));
    }

    @Test
    void testErrorIdNotFoundReturnsNotFoundShapeAndLogsAudit() {
        UUID adminId = UUID.randomUUID();
        when(lokiClient.query(anyString(), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse response = diagnosticsService.lookup(adminId, "E2ERR001", "auto", null, null);

        assertFalse(response.found());
        assertNull(response.requestId());
        assertEquals("E2ERR001", response.errorId());
        assertEquals("NOT_FOUND_IN_LOGS", response.rootCause().kind());
        assertTrue(response.timeline().isEmpty());
        assertFalse(response.rawAvailable());

        assertEquals(1, listAppender.list.size());
        ILoggingEvent audit = listAppender.list.get(0);
        assertTrue(audit.getFormattedMessage().contains("Admin diagnostics lookup"));
        boolean foundRef = Arrays.stream(audit.getArgumentArray())
                .anyMatch(arg -> arg != null && arg.toString().contains("ref=E2ERR001"));
        assertTrue(foundRef);
    }

    @Test
    void testErrorIdPathLiftsRequestIdFromJsonAndQueriesServerClientFaro() {
        UUID adminId = UUID.randomUUID();
        String errorLine = "{\"event\":\"request.failed\",\"errorId\":\"E2EERR01\",\"requestId\":\"req-lifted-1\",\"level\":\"ERROR\",\"message\":\"boom\"}";
        LokiQueryClient.LokiLogLine line1 = new LokiQueryClient.LokiLogLine(
                1700000000000000000L, Instant.ofEpochSecond(1700000000L), "server", Map.of("level", "ERROR"), errorLine
        );

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | errorId=\"E2EERR01\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(line1), false));

        String httpLine = "{\"event\":\"http.request\",\"requestId\":\"req-lifted-1\",\"method\":\"POST\",\"route\":\"/api/v1/accounts\",\"status\":500,\"durationMs\":30,\"version\":\"1.0.0\"}";
        LokiQueryClient.LokiLogLine line2 = new LokiQueryClient.LokiLogLine(
                1700000000010000000L, Instant.ofEpochSecond(1700000000L, 10000000L), "server", Map.of("level", "INFO"), httpLine
        );

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-lifted-1\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(line2), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-lifted-1\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-lifted-1\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "E2EERR01", "errorId", null, null);

        assertTrue(res.found());
        assertEquals("req-lifted-1", res.requestId());
        assertEquals("E2EERR01", res.errorId());
        assertNotNull(res.request());
        assertEquals("/api/v1/accounts", res.request().route());
        assertEquals(500, res.request().status());
        assertEquals("1.0.0", res.request().version());
        assertEquals(2, res.timeline().size());
        assertTrue(res.rawAvailable());
    }

    @Test
    void testErrorIdPathLiftsRequestIdFromSubstringFallback() {
        UUID adminId = UUID.randomUUID();
        // Plaintext with substring requestId=req-sub-2,
        String errorLine = "Error happened: requestId=req-sub-2, cause=Database down";
        LokiQueryClient.LokiLogLine line1 = new LokiQueryClient.LokiLogLine(
                1700000000000000000L, Instant.ofEpochSecond(1700000000L), "server", Map.of("level", "ERROR"), errorLine
        );

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | errorId=\"E2EERR02\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(line1), false));
        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-sub-2\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-sub-2\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-sub-2\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "E2EERR02", "errorId", null, null);
        assertEquals("req-sub-2", res.requestId());
    }

    @Test
    void testLiftedRequestIdFailingRegexIsIgnored() {
        UUID adminId = UUID.randomUUID();
        String errorLine = "{\"event\":\"request.failed\",\"errorId\":\"E2EERR03\",\"requestId\":\"bad$req$id\"}";
        LokiQueryClient.LokiLogLine line1 = new LokiQueryClient.LokiLogLine(
                1700000000000000000L, Instant.ofEpochSecond(1700000000L), "server", Map.of("level", "ERROR"), errorLine
        );

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | errorId=\"E2EERR03\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(line1), false));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "E2EERR03", "errorId", null, null);

        assertTrue(res.found());
        assertNull(res.requestId());
        // Verify no second query was made
        verify(lokiClient, times(1)).query(anyString(), any(), any(), anyInt());
    }

    @Test
    void testSessionQueriesTriggeredWhenSessionIdLiftedWithPlusMinus5MinWindow() {
        UUID adminId = UUID.randomUUID();
        Instant reqAt = Instant.parse("2026-09-01T12:00:00Z");

        String serverLine = "{\"event\":\"http.request\",\"requestId\":\"req-sess-1\",\"sessionId\":\"sess-abc-123\",\"method\":\"GET\",\"route\":\"/test\",\"status\":200}";
        LokiQueryClient.LokiLogLine l1 = new LokiQueryClient.LokiLogLine(
                reqAt.getEpochSecond() * 1_000_000_000L, reqAt, "server", Map.of("level", "INFO"), serverLine
        );

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-sess-1\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l1), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-sess-1\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-sess-1\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        ArgumentCaptor<Instant> startCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> endCaptor = ArgumentCaptor.forClass(Instant.class);

        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | sessionId=\"sess-abc-123\""), startCaptor.capture(), endCaptor.capture(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"sess-abc-123\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "req-sess-1", "requestId", null, null);
        assertTrue(res.found());

        assertEquals(reqAt.minus(Duration.ofMinutes(5)), startCaptor.getValue());
        assertEquals(reqAt.plus(Duration.ofMinutes(5)), endCaptor.getValue());
    }

    @Test
    void testDedupOfIdenticalTimestampAndLineAcrossQueries() {
        UUID adminId = UUID.randomUUID();
        String lineText = "{\"message\":\"duplicate log\"}";
        LokiQueryClient.LokiLogLine l1 = new LokiQueryClient.LokiLogLine(1000L, Instant.ofEpochSecond(1), "server", Map.of(), lineText);
        LokiQueryClient.LokiLogLine l2 = new LokiQueryClient.LokiLogLine(1000L, Instant.ofEpochSecond(1), "server", Map.of(), lineText);

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-dedup\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l1, l2), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-dedup\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-dedup\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "req-dedup", "requestId", null, null);
        assertEquals(1, res.timeline().size());
    }

    @Test
    void testTruncatedIsOrAcrossQueries() {
        UUID adminId = UUID.randomUUID();
        LokiQueryClient.LokiLogLine l1 = new LokiQueryClient.LokiLogLine(1000L, Instant.now(), "client", Map.of(), "{\"message\":\"client log\"}");
        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-trunc\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-trunc\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l1), true)); // Truncated!
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-trunc\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "req-trunc", "requestId", null, null);
        assertTrue(res.truncated());
    }

    @Test
    void testClientAndFaroFailuresSwallowedWhileServerFailurePropagatesUnwrapped() {
        UUID adminId = UUID.randomUUID();

        // 1. Client/Faro fail -> swallowed
        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-swallow\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(
                        new LokiQueryClient.LokiLogLine(1000L, Instant.now(), "server", Map.of(), "{\"message\":\"ok\"}")
                ), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-swallow\""), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("client loki error"));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-swallow\""), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("faro loki error"));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "req-swallow", "requestId", null, null);
        assertTrue(res.found());

        // 2. Server fails with ApiStatusException 503 -> propagates unwrapped
        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-server-fail\""), any(), any(), anyInt()))
                .thenThrow(new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DIAGNOSTICS_UNAVAILABLE", "Loki down"));

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> diagnosticsService.lookup(adminId, "req-server-fail", "requestId", null, null));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex.getCode());
    }

    @Test
    void testTimelineParsingFieldsWhitelistAndUserEmailResolution() {
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User testUser = new User("user@example.test", "hash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        String jsonLine = String.format("""
                {
                  "event": "http.request",
                  "message": "Processed request",
                  "logger_name": "com.financeos.Controller",
                  "level": "INFO",
                  "method": "GET",
                  "route": "/api/v1/accounts",
                  "status": 200,
                  "durationMs": 15,
                  "userId": "%s",
                  "version": "1.2.3",
                  "slow": false,
                  "nullField": null,
                  "unwhitelistedSecret": "hidden-secret",
                  "stack_trace": "stack trace line 1"
                }
                """, userId);

        LokiQueryClient.LokiLogLine l1 = new LokiQueryClient.LokiLogLine(1000L, Instant.now(), "server", Map.of(), jsonLine);
        // Plaintext non-json line
        LokiQueryClient.LokiLogLine l2 = new LokiQueryClient.LokiLogLine(2000L, Instant.now(), "client", Map.of("level", "WARN"), "Plain text error message");

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-parse\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l1, l2), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-parse\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-parse\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        DiagnosticsLookupResponse res = diagnosticsService.lookup(adminId, "req-parse", "requestId", null, null);

        assertNotNull(res.request());
        assertEquals("user@example.test", res.request().userEmail());
        assertEquals("1.2.3", res.request().version());

        assertEquals(2, res.timeline().size());
        var t1 = res.timeline().get(0);
        assertEquals("Processed request", t1.message());
        assertEquals("stack trace line 1", t1.stackTrace());
        assertEquals("com.financeos.Controller", t1.logger());
        assertEquals(200, t1.fields().get("status"));
        assertEquals(15, t1.fields().get("durationMs"));
        assertFalse((Boolean) t1.fields().get("slow"));
        assertNull(t1.fields().get("nullField"));
        assertNull(t1.fields().get("unwhitelistedSecret"));

        var t2 = res.timeline().get(1);
        assertEquals("Plain text error message", t2.message());
        assertEquals("WARN", t2.level());
    }

    @Test
    void testRawLookupReturnsUntouchedLinesAndLogsRawAuditEvent() {
        UUID adminId = UUID.randomUUID();
        String rawLine = "{\"raw\":\"json\"}";
        LokiQueryClient.LokiLogLine l1 = new LokiQueryClient.LokiLogLine(
                1000L, Instant.parse("2026-09-01T10:00:00Z"), "server", Map.of("env", "prod"), rawLine
        );

        when(lokiClient.query(eq("{service=\"financeos-server\"} | json | requestId=\"req-raw\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(l1), false));
        when(lokiClient.query(eq("{service=\"financeos-client\"} | json | requestId=\"req-raw\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));
        when(lokiClient.query(eq("{kind=~\".+\"} |= \"req-raw\""), any(), any(), anyInt()))
                .thenReturn(new LokiQueryClient.LokiQueryResult(List.of(), false));

        List<RawLogEntry> rawEntries = diagnosticsService.raw(adminId, "req-raw", "requestId", null, null);

        assertEquals(1, rawEntries.size());
        assertEquals(rawLine, rawEntries.get(0).line());
        assertEquals("server", rawEntries.get(0).source());
        assertEquals("prod", rawEntries.get(0).labels().get("env"));

        assertEquals(1, listAppender.list.size());
        ILoggingEvent audit = listAppender.list.get(0);
        assertTrue(audit.getFormattedMessage().contains("Admin diagnostics raw lookup"));
    }
}
