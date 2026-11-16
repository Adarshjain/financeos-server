package com.financeos.gmail.client;

import com.financeos.core.observability.Ops;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.gmail.Gmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.DoubleSupplier;

/**
 * Retries one idempotent Gmail API read on transient failures. The Google client has no retry of its own
 * here (the only handler installed is the credential's, which refreshes the token on some 401s), so a single
 * 5xx or read timeout used to fail the whole job.
 *
 * <p>Transient: HTTP 429/500/502/503/504 and socket timeout, connect failure, connection reset; up to
 * {@value #MAX_ATTEMPTS} attempts in total with a backoff of about 1 s then 3 s (jittered). A 429 may carry
 * Retry-After, honoured up to {@value #MAX_WAIT_MS} ms. A 401 force-refreshes the access token once and retries
 * once; if it still fails (or the refresh itself is rejected) the original handling applies unchanged.
 * Everything else is thrown as is. Only use it for reads.
 */
public class GmailRetry {

    static final int MAX_ATTEMPTS = 3;
    static final long MAX_WAIT_MS = 10_000;
    private static final long[] BACKOFF_MS = {1_000, 3_000};
    private static final double JITTER = 0.25;
    private static final Logger log = LoggerFactory.getLogger(GmailRetry.class);

    /** The production instance: real sleeping, random jitter. */
    public static final GmailRetry DEFAULT = new GmailRetry(Thread::sleep, Math::random);

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    public interface Call<T> {
        T run() throws IOException;
    }

    /** Forces a new access token; false when none could be obtained. May throw the token server's rejection. */
    @FunctionalInterface
    public interface TokenRefresher {
        boolean refresh() throws IOException;
    }

    private final Sleeper sleeper;
    private final DoubleSupplier random;

    public GmailRetry(Sleeper sleeper, DoubleSupplier random) {
        this.sleeper = sleeper;
        this.random = random;
    }

    /** Runs a read on {@code service}; its credential (if any) is the token refresher for a 401. */
    public <T> T call(String call, Gmail service, Call<T> action) throws IOException {
        return call(call, refresher(service), action);
    }

    public <T> T call(String call, TokenRefresher refresher, Call<T> action) throws IOException {
        String op = Ops.gmail(call);
        int attempt = 1;
        boolean refreshed = false;
        while (true) {
            try {
                return action.run();
            } catch (IOException e) {
                Integer status = e instanceof HttpResponseException hre ? hre.getStatusCode() : null;
                boolean fromTokenServer = e instanceof TokenResponseException;
                if (status != null && status == 401 && !fromTokenServer && !refreshed && refresher != null) {
                    refreshed = true;
                    if (!refresher.refresh()) {
                        throw e;
                    }
                    logRetry(op, attempt, e, 0);
                    continue;
                }
                if (attempt >= MAX_ATTEMPTS || !transient_(e, status)) {
                    throw e;
                }
                long wait = waitMs(e, status, attempt);
                logRetry(op, attempt, e, wait);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                attempt++;
            }
        }
    }

    static TokenRefresher refresher(Gmail service) {
        var factory = service.getRequestFactory();
        HttpRequestInitializer init = factory == null ? null : factory.getInitializer();
        return init instanceof Credential credential ? credential::refreshToken : null;
    }

    private static boolean transient_(IOException e, Integer status) {
        if (status != null) {
            return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
        }
        if (e instanceof SocketTimeoutException || e instanceof ConnectException) {
            return true;
        }
        return e instanceof SocketException && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.ROOT).contains("reset");
    }

    private long waitMs(IOException e, Integer status, int attempt) {
        if (status != null && status == 429 && e instanceof HttpResponseException hre) {
            Long retryAfter = retryAfterMs(hre.getHeaders() == null ? null : hre.getHeaders().getRetryAfter());
            if (retryAfter != null) {
                return Math.min(retryAfter, MAX_WAIT_MS);
            }
        }
        long base = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length) - 1];
        double factor = 1 + JITTER * (2 * random.getAsDouble() - 1);
        return Math.round(base * factor);
    }

    /** Retry-After as delta-seconds or an HTTP date; null when absent or unreadable. */
    static Long retryAfterMs(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            return Math.min(Math.max(0, Long.parseLong(v)), MAX_WAIT_MS / 1000) * 1000;
        } catch (NumberFormatException ignored) {
            // fall through to the date form
        }
        try {
            long ms = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis();
            return Math.max(0, ms);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static void logRetry(String op, int attempt, IOException e, long waitMs) {
        String cause = e instanceof HttpResponseException hre ? "HttpStatus status=" + hre.getStatusCode()
                : e.getClass().getSimpleName();
        log.warn("event=ext.retry op={} attempt={} cause={} waitMs={}", op, attempt, cause, waitMs);
    }
}
