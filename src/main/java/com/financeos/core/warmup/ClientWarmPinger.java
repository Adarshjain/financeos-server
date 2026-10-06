package com.financeos.core.warmup;

import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Vercel SSR function warm by requesting the client's {@code /login} page on a fixed delay.
 *
 * Prod data (2026-10-06, 7 days): 333 Vercel coldstarts; navigations that hit one had TTFB p50 2.6 s
 * vs 0.63 s warm, while the API itself answered in ~26 ms. {@code /login} without a session cookie is
 * rendered per request but never calls this API, so the ping costs the backend nothing.
 *
 * The scheduler has a single thread shared with the job poller, so the request is sent async and
 * {@link #ping()} returns immediately. Failures are logged once per outage, not on every tick.
 */
@Component
@ConditionalOnProperty(name = "warm-ping.enabled", havingValue = "true")
public class ClientWarmPinger {

    private static final Logger log = LoggerFactory.getLogger(ClientWarmPinger.class);
    static final String USER_AGENT = "financeos-warm-ping";

    private final HttpClient httpClient;
    private final URI target;
    private final Duration timeout;
    private volatile boolean failing;

    @Autowired
    public ClientWarmPinger(@Value("${warm-ping.url}") String url,
                            @Value("${warm-ping.timeout-ms:20000}") long timeoutMs) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), URI.create(url),
                Duration.ofMillis(timeoutMs));
    }

    ClientWarmPinger(HttpClient httpClient, URI target, Duration timeout) {
        this.httpClient = httpClient;
        this.target = target;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${warm-ping.interval-ms:300000}",
               initialDelayString = "${warm-ping.initial-delay-ms:60000}")
    public void ping() {
        send();
    }

    /** Sends one ping; completes with whether the client answered with a non-error status. */
    CompletableFuture<Boolean> send() {
        HttpRequest request = HttpRequest.newBuilder(target)
                .header("User-Agent", USER_AGENT)
                .timeout(timeout)
                .GET()
                .build();
        long startNs = System.nanoTime();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((response, error) -> {
                    long durationMs = (System.nanoTime() - startNs) / 1_000_000;
                    Integer status = response != null ? response.statusCode() : null;
                    boolean ok = error == null && status != null && status < 400;
                    record(ok, status, error, durationMs);
                    return ok;
                });
    }

    private void record(boolean ok, Integer status, Throwable error, long durationMs) {
        if (ok) {
            if (failing) {
                failing = false;
                log.info("Client warm ping recovered: status={}, durationMs={}", status, durationMs,
                        StructuredArguments.keyValue("event", Events.CLIENT_WARM_PING_RECOVERED),
                        StructuredArguments.keyValue("status", status),
                        StructuredArguments.keyValue("durationMs", durationMs));
            } else {
                log.debug("Client warm ping ok: status={}, durationMs={}", status, durationMs);
            }
            return;
        }
        if (!failing) {
            failing = true;
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            String reason = cause != null ? cause.getClass().getSimpleName() : "HTTP " + status;
            log.warn("Client warm ping failed: target={}, reason={}, durationMs={}", target, reason, durationMs,
                    StructuredArguments.keyValue("event", Events.CLIENT_WARM_PING_FAILED),
                    StructuredArguments.keyValue("status", status),
                    StructuredArguments.keyValue("reason", reason),
                    StructuredArguments.keyValue("durationMs", durationMs));
        }
    }
}
