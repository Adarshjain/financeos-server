package com.financeos.llm.provider;

import com.financeos.llm.LlmException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LlmHttpSupport#sendWithDeadline} bounds headers AND body. The prod failure mode it guards:
 * OpenRouter sends headers at once, then trickles whitespace for minutes while the model runs.
 */
class LlmHttpSupportDeadlineTest {

    private HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/fast", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // Headers immediately, then a body that keeps trickling whitespace for ~10 s.
        server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write('\n');
                    out.flush();
                    Thread.sleep(100);
                }
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // client gave up — expected
            }
        });
        // Complete response, but only after 300 ms.
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "done".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path)).GET().build();
    }

    @Test
    void returnsTheResponseWhenItCompletesInsideTheDeadline() throws Exception {
        HttpResponse<String> response = LlmHttpSupport.sendWithDeadline(client, get("/fast"), 5_000);

        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
    }

    @Test
    void abortsABodyThatTricklesPastTheDeadline() {
        long start = System.nanoTime();

        HttpTimeoutException ex = assertThrows(HttpTimeoutException.class,
                () -> LlmHttpSupport.sendWithDeadline(client, get("/trickle"), 500));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 3_000, "deadline must cut the body short, took " + elapsedMs + "ms");
        assertTrue(ex.getMessage().contains("500ms"));
    }

    @Test
    void deadlineTimeoutIsClassifiedRetryableSoFailoverMovesOn() {
        LlmException ex = assertThrows(LlmException.class, () -> LlmHttpSupport.executeAndHandleExceptions(
                () -> LlmHttpSupport.sendWithDeadline(client, get("/trickle"), 300), "openrouter"));

        assertEquals(LlmException.Kind.RETRYABLE, ex.getKind());
        assertTrue(ex.getMessage().contains("IO/Timeout error"));
    }

    @Test
    void nonPositiveTimeoutMeansNoDeadline() throws Exception {
        HttpResponse<String> response = LlmHttpSupport.sendWithDeadline(client, get("/slow"), 0);

        assertEquals("done", response.body());
    }

    @Test
    void transportFailureSurfacesAsTheUnderlyingIOExceptionNotAnExecutionWrapper() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closedPort + "/")).GET().build();

        assertThrows(IOException.class, () -> LlmHttpSupport.sendWithDeadline(client, request, 2_000));

        LlmException classified = assertThrows(LlmException.class, () -> LlmHttpSupport.executeAndHandleExceptions(
                () -> LlmHttpSupport.sendWithDeadline(client, request, 2_000), "gemini"));
        assertEquals(LlmException.Kind.RETRYABLE, classified.getKind());
    }
}
