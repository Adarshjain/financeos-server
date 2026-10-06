package com.financeos.llm.provider;

import com.financeos.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LlmHttpSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmHttpSupport.class);

    public static void classifyStatus(int statusCode, String body, Long retryAfter, String providerId) {
        if (statusCode != 200) {
            String truncatedBody = truncate(body, 200);
            String fullCappedBody = truncate(body, 4000);
            if (statusCode == 429 || statusCode >= 500) {
                log.error("Provider {} returned retryable HTTP {}: {}", providerId, statusCode, body);
                throw new LlmException(LlmException.Kind.RETRYABLE, providerId, statusCode, retryAfter, "HTTP " + statusCode + ": " + truncatedBody, fullCappedBody);
            } else {
                log.error("Provider {} returned fatal HTTP {}: {}", providerId, statusCode, body);
                throw new LlmException(LlmException.Kind.FATAL, providerId, statusCode, retryAfter, "HTTP " + statusCode + ": " + truncatedBody, fullCappedBody);
            }
        }
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "… (truncated)";
    }

    public static Long parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(val -> {
                    try {
                        return Long.parseLong(val.trim());
                    } catch (NumberFormatException e) {
                        try {
                            ZonedDateTime date = ZonedDateTime.parse(val.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                            long diff = Duration.between(Instant.now(), date.toInstant()).getSeconds();
                            return diff > 0 ? diff : 0L;
                        } catch (Exception ex) {
                            return null;
                        }
                    }
                })
                .orElse(null);
    }

    /**
     * Sends {@code request} and bounds the WHOLE exchange — headers and body — by {@code timeoutMs}.
     *
     * {@link java.net.http.HttpRequest.Builder#timeout} only bounds the wait for response headers.
     * OpenRouter sends headers immediately and then trickles keep-alive whitespace while the model
     * runs, so a 90 s header timeout let single calls run 4–6 minutes in prod. A non-positive
     * {@code timeoutMs} means no deadline.
     */
    public static HttpResponse<String> sendWithDeadline(HttpClient client, HttpRequest request, long timeoutMs)
            throws Exception {
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return timeoutMs > 0 ? future.get(timeoutMs, TimeUnit.MILLISECONDS) : future.get();
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("No complete response within " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    @FunctionalInterface
    public interface HttpExecution {
        HttpResponse<String> execute() throws Exception;
    }

    public static HttpResponse<String> executeAndHandleExceptions(HttpExecution execution, String providerId) {
        try {
            return execution.execute();
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.RETRYABLE, providerId, null, null, "IO/Timeout error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.FATAL, providerId, null, null, "Request interrupted", e);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.FATAL, providerId, null, null, "Unexpected error: " + e.getMessage(), e);
        }
    }
}
