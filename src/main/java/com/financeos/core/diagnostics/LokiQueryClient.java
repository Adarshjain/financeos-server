package com.financeos.core.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.AppConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class LokiQueryClient {

    private static final Pattern REF_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_]{1,64}$");

    private final AppConfigProperties appConfig;
    private final String grafanaLokiHost;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LokiQueryClient(
            AppConfigProperties appConfig,
            @Value("${GRAFANA_LOKI_HOST:}") String grafanaLokiHost,
            ObjectMapper objectMapper) {
        this.appConfig = appConfig;
        this.grafanaLokiHost = grafanaLokiHost;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record LokiLogLine(
            long timestampNs,
            Instant at,
            String source,
            Map<String, String> labels,
            String line) {}

    public record LokiQueryResult(
            List<LokiLogLine> lines,
            boolean truncated) {}

    public void validateRef(String ref) {
        if (ref == null || !REF_PATTERN.matcher(ref).matches()) {
            throw new ValidationException("Invalid reference format: " + ref);
        }
    }

    public LokiQueryResult query(String logQl, Instant start, Instant end, int limit) {
        String baseUrl = resolveBaseUrl();
        String user = resolveUser();
        String token = resolveToken();

        if (user == null || user.isBlank() || token == null || token.isBlank()) {
            throw new ApiStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DIAGNOSTICS_UNAVAILABLE",
                    "Loki credentials are not configured. Please set LOKI_QUERY_USER and LOKI_QUERY_TOKEN.");
        }

        long startNs = start.getEpochSecond() * 1_000_000_000L + start.getNano();
        long endNs = end.getEpochSecond() * 1_000_000_000L + end.getNano();

        String url = String.format(
                "%s/loki/api/v1/query_range?query=%s&start=%d&end=%d&limit=%d&direction=forward",
                baseUrl.replaceAll("/+$", ""),
                URLEncoder.encode(logQl, StandardCharsets.UTF_8),
                startNs,
                endNs,
                limit);

        String authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(appConfig.getDiagnostics().getLoki().getTimeoutSeconds()))
                .GET()
                .build();

        return executeWithRetry(request, limit);
    }

    private LokiQueryResult executeWithRetry(HttpRequest request, int limit) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 503) {
                log.warn("Loki returned 503, retrying once after 2s...");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DIAGNOSTICS_UNAVAILABLE", "Loki query interrupted");
                }
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }

            return handleResponse(response, limit);
        } catch (HttpTimeoutException te) {
            log.error("Loki query timed out", te);
            throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DIAGNOSTICS_UNAVAILABLE", "Loki query timed out. Please check network connectivity.");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Loki query failed", e);
            throw new ApiStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DIAGNOSTICS_UNAVAILABLE", "Failed to communicate with Loki: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private LokiQueryResult handleResponse(HttpResponse<String> response, int limit) throws IOException {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ApiStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DIAGNOSTICS_UNAVAILABLE",
                    "Loki credentials lack read scope. Please configure LOKI_QUERY_TOKEN with logs:read permissions.");
        }
        if (status == 429) {
            throw new ApiStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "Loki rate limit exceeded. Please try again later.");
        }
        if (status >= 500) {
            throw new ApiStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DIAGNOSTICS_UNAVAILABLE",
                    "Loki service unavailable (status " + status + ").");
        }
        if (status != 200) {
            throw new ApiStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "DIAGNOSTICS_UNAVAILABLE",
                    "Loki query returned status " + status + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode resultNode = root.path("data").path("result");

        List<LokiLogLine> lines = new ArrayList<>();
        boolean truncated = false;

        if (resultNode.isArray()) {
            for (JsonNode streamObj : resultNode) {
                Map<String, String> labels = new HashMap<>();
                JsonNode streamLabels = streamObj.path("stream");
                if (streamLabels.isObject()) {
                    streamLabels.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
                }

                String source = determineSource(labels);
                JsonNode values = streamObj.path("values");
                if (values.isArray()) {
                    if (values.size() >= limit) {
                        truncated = true;
                    }
                    for (JsonNode val : values) {
                        if (val.isArray() && val.size() >= 2) {
                            String tsStr = val.get(0).asText();
                            String line = val.get(1).asText();
                            try {
                                long tsNs = Long.parseLong(tsStr);
                                Instant at = Instant.ofEpochSecond(tsNs / 1_000_000_000L, tsNs % 1_000_000_000L);
                                lines.add(new LokiLogLine(tsNs, at, source, labels, line));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }

        lines.sort(Comparator.comparingLong(LokiLogLine::timestampNs));
        return new LokiQueryResult(lines, truncated);
    }

    private String determineSource(Map<String, String> labels) {
        String service = labels.get("service");
        if ("financeos-server".equals(service)) {
            return "server";
        }
        if ("financeos-client".equals(service)) {
            return "client";
        }
        if (labels.containsKey("kind") || labels.containsKey("app_id") || labels.containsKey("app_key")) {
            return "faro";
        }
        return "server";
    }

    private String resolveBaseUrl() {
        String url = appConfig.getDiagnostics().getLoki().getUrl();
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (grafanaLokiHost != null && !grafanaLokiHost.isBlank()) {
            String host = grafanaLokiHost.trim();
            if (!host.startsWith("http://") && !host.startsWith("https://")) {
                return "https://" + host;
            }
            return host;
        }
        throw new ApiStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DIAGNOSTICS_UNAVAILABLE",
                "Loki query URL is not configured. Please set LOKI_QUERY_URL or GRAFANA_LOKI_HOST.");
    }

    private String resolveUser() {
        String user = appConfig.getDiagnostics().getLoki().getUser();
        if (user != null && !user.isBlank()) {
            return user;
        }
        return System.getenv("GRAFANA_LOKI_USER");
    }

    private String resolveToken() {
        String token = appConfig.getDiagnostics().getLoki().getToken();
        if (token != null && !token.isBlank()) {
            return token;
        }
        return System.getenv("GRAFANA_CLOUD_TOKEN");
    }
}
