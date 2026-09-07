package com.financeos.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.AppConfigProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LokiQueryClientTest {

    private record RecordedRequest(String method, String path, String query, String authHeader) {}

    private static HttpServer server;
    private static String serverBase;
    private static final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();
    private static final AtomicInteger retryCount = new AtomicInteger(0);

    private ObjectMapper objectMapper;
    private AppConfigProperties appConfig;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/query_range", exchange -> {
            record(exchange);
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();

            if (query != null && query.contains("401-test")) {
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            } else if (query != null && query.contains("403-test")) {
                respond(exchange, 403, "{\"error\":\"forbidden\"}");
            } else if (query != null && query.contains("429-test")) {
                respond(exchange, 429, "{\"error\":\"rate limited\"}");
            } else if (query != null && query.contains("500-test")) {
                respond(exchange, 500, "{\"error\":\"internal server error\"}");
            } else if (query != null && query.contains("400-test")) {
                respond(exchange, 400, "{\"error\":\"bad parse expression\"}");
            } else if (query != null && query.contains("503-retry-test")) {
                int c = retryCount.incrementAndGet();
                if (c == 1) {
                    respond(exchange, 503, "{\"error\":\"temporary error\"}");
                } else {
                    respond(exchange, 200, "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}");
                }
            } else if (query != null && query.contains("503-fail-test")) {
                respond(exchange, 503, "{\"error\":\"service permanently down\"}");
            } else {
                // Default 200 response
                respond(exchange, 200, "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[]}}");
            }
        });

        server.start();
        serverBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        recordedRequests.clear();
        retryCount.set(0);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        appConfig = new AppConfigProperties();
        appConfig.getDiagnostics().getLoki().setUrl(serverBase);
        appConfig.getDiagnostics().getLoki().setUser("loki-user");
        appConfig.getDiagnostics().getLoki().setToken("loki-token");
        appConfig.getDiagnostics().getLoki().setTimeoutSeconds(5);
    }

    private static void record(HttpExchange exchange) {
        recordedRequests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Authorization")
        ));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void testRequestShapeAndBasicAuth() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        Instant start = Instant.ofEpochSecond(1700000000L, 123456789L);
        Instant end = Instant.ofEpochSecond(1700000100L, 987654321L);
        String logQl = "{service=\"financeos-server\"} |= \"error\"";

        client.query(logQl, start, end, 50);

        assertEquals(1, recordedRequests.size());
        RecordedRequest req = recordedRequests.get(0);
        assertEquals("GET", req.method());
        assertEquals("/loki/api/v1/query_range", req.path());

        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString("loki-user:loki-token".getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedAuth, req.authHeader());

        assertTrue(req.query().contains("query="));
        assertTrue(req.query().contains("direction=forward"));
        assertTrue(req.query().contains("limit=50"));
        assertTrue(req.query().contains("start=1700000000123456789"));
        assertTrue(req.query().contains("end=1700000100987654321"));

        String decodedQuery = URLDecoder.decode(req.query(), StandardCharsets.UTF_8);
        assertTrue(decodedQuery.contains(logQl));
    }

    @Test
    void test200ParseMergesAndSortsStreamsWithSourceMapping() throws IOException {
        // Multi-stream response
        String multiStreamJson = """
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [
                      {
                        "stream": {"service": "financeos-server", "level": "ERROR"},
                        "values": [
                          ["1700000000200000000", "{\\"message\\":\\"server msg 2\\"}"]
                        ]
                      },
                      {
                        "stream": {"service": "financeos-client", "level": "WARN"},
                        "values": [
                          ["1700000000100000000", "{\\"message\\":\\"client msg 1\\"}"],
                          ["invalid-ts", "ignored line"]
                        ]
                      },
                      {
                        "stream": {"kind": "session", "app_id": "faro"},
                        "values": [
                          ["1700000000300000000", "faro log line 3"]
                        ]
                      }
                    ]
                  }
                }
                """;

        HttpServer testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/loki/api/v1/query_range", ex -> respond(ex, 200, multiStreamJson));
        testServer.start();

        try {
            appConfig.getDiagnostics().getLoki().setUrl("http://127.0.0.1:" + testServer.getAddress().getPort());
            LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

            LokiQueryClient.LokiQueryResult result = client.query("test", Instant.now(), Instant.now(), 50);

            assertFalse(result.truncated());
            assertEquals(3, result.lines().size());

            // Sorted by ts
            assertEquals(1700000000100000000L, result.lines().get(0).timestampNs());
            assertEquals("client", result.lines().get(0).source());
            assertEquals("WARN", result.lines().get(0).labels().get("level"));

            assertEquals(1700000000200000000L, result.lines().get(1).timestampNs());
            assertEquals("server", result.lines().get(1).source());

            assertEquals(1700000000300000000L, result.lines().get(2).timestampNs());
            assertEquals("faro", result.lines().get(2).source());
            assertEquals("faro log line 3", result.lines().get(2).line());
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void testTruncatedFlagSetWhenStreamValuesReachLimit() throws IOException {
        String truncatedJson = """
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [
                      {
                        "stream": {"service": "financeos-server"},
                        "values": [
                          ["1700000000100000000", "msg 1"],
                          ["1700000000200000000", "msg 2"]
                        ]
                      }
                    ]
                  }
                }
                """;

        HttpServer testServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testServer.createContext("/loki/api/v1/query_range", ex -> respond(ex, 200, truncatedJson));
        testServer.start();

        try {
            appConfig.getDiagnostics().getLoki().setUrl("http://127.0.0.1:" + testServer.getAddress().getPort());
            LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

            LokiQueryClient.LokiQueryResult result = client.query("test", Instant.now(), Instant.now(), 2);
            assertTrue(result.truncated());
            assertEquals(2, result.lines().size());

            LokiQueryClient.LokiQueryResult notTruncated = client.query("test", Instant.now(), Instant.now(), 10);
            assertFalse(notTruncated.truncated());
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void test401And403Become503WithLokiQueryTokenMessage() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        ApiStatusException ex401 = assertThrows(ApiStatusException.class,
                () -> client.query("401-test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex401.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex401.getCode());
        assertTrue(ex401.getMessage().contains("LOKI_QUERY_TOKEN"));

        ApiStatusException ex403 = assertThrows(ApiStatusException.class,
                () -> client.query("403-test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex403.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex403.getCode());
        assertTrue(ex403.getMessage().contains("LOKI_QUERY_TOKEN"));
    }

    @Test
    void test429BecomesRateLimited() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("429-test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertEquals("RATE_LIMITED", ex.getCode());
    }

    @Test
    void test503RetriesOnceAndSucceeds() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        LokiQueryClient.LokiQueryResult result = client.query("503-retry-test", Instant.now(), Instant.now(), 10);
        assertNotNull(result);
        assertEquals(2, retryCount.get());
    }

    @Test
    void test503TwiceBecomes503() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("503-fail-test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex.getCode());
    }

    @Test
    void test500Becomes503() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("500-test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex.getCode());
    }

    @Test
    void test400Becomes503WithResponseBodyInMessage() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("400-test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex.getCode());
        assertTrue(ex.getMessage().contains("bad parse expression"));
    }

    @Test
    void testConnectionRefusedBecomes503WithExceptionClassOrMessage() {
        appConfig.getDiagnostics().getLoki().setUrl("http://127.0.0.1:1");
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex.getCode());
        assertTrue(ex.getMessage().contains("Failed to communicate with Loki") || ex.getMessage().contains("ConnectException"));
    }

    @Test
    void testBlankUserOrTokenThrows503NamingEnvVarsWithoutMakingHttpCall() {
        appConfig.getDiagnostics().getLoki().setUser("");
        appConfig.getDiagnostics().getLoki().setToken("");
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex.getCode());
        assertTrue(ex.getMessage().contains("LOKI_QUERY_USER"));
        assertTrue(ex.getMessage().contains("LOKI_QUERY_TOKEN"));
        assertEquals(0, recordedRequests.size());
    }

    @Test
    void testBlankUrlAndBlankHostThrows503NamingLokiQueryUrl() {
        appConfig.getDiagnostics().getLoki().setUrl("");
        LokiQueryClient client = new LokiQueryClient(appConfig, "", objectMapper);

        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("DIAGNOSTICS_UNAVAILABLE", ex.getCode());
        assertTrue(ex.getMessage().contains("LOKI_QUERY_URL") || ex.getMessage().contains("GRAFANA_LOKI_HOST"));
    }

    @Test
    void testHostWithoutSchemePrependsHttps() {
        appConfig.getDiagnostics().getLoki().setUrl("");
        // Host without scheme
        LokiQueryClient client = new LokiQueryClient(appConfig, "loki-gateway.example.com", objectMapper);

        // Making query will try https://loki-gateway.example.com and throw connection/lookup failure
        ApiStatusException ex = assertThrows(ApiStatusException.class,
                () -> client.query("test", Instant.now(), Instant.now(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
    }

    @Test
    void testValidateRefAcceptsValidAndRejectsInvalid() {
        LokiQueryClient client = new LokiQueryClient(appConfig, null, objectMapper);

        // Valid
        assertDoesNotThrow(() -> client.validateRef("e2ereq0000000000001a"));
        assertDoesNotThrow(() -> client.validateRef("E2EERR01"));
        assertDoesNotThrow(() -> client.validateRef("smoke-123"));
        assertDoesNotThrow(() -> client.validateRef("a_b-1"));

        // Invalid
        assertThrows(ValidationException.class, () -> client.validateRef(null));
        assertThrows(ValidationException.class, () -> client.validateRef(""));
        assertThrows(ValidationException.class, () -> client.validateRef("   "));
        assertThrows(ValidationException.class, () -> client.validateRef("bad$ref"));
        assertThrows(ValidationException.class, () -> client.validateRef("a".repeat(65)));
        assertThrows(ValidationException.class, () -> client.validateRef("ref'with'quotes"));
        assertThrows(ValidationException.class, () -> client.validateRef("ref\"quotes"));
    }
}
