package com.financeos.domain.instrument.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Component
public class YahooDividendEventsClient {

    private static final Logger log = LoggerFactory.getLogger(YahooDividendEventsClient.class);

    private final PriceProperties priceProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public record DividendEvent(LocalDate exDate, BigDecimal amount) {}

    public YahooDividendEventsClient(PriceProperties priceProperties, ObjectMapper objectMapper) {
        this.priceProperties = priceProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record FetchResult(List<DividendEvent> events, boolean success) {}

    public List<DividendEvent> fetchDividendEvents(String symbol, Instant period1, Instant period2) {
        return fetchDividendEventsWithStatus(symbol, period1, period2).events();
    }

    public FetchResult fetchDividendEventsWithStatus(String symbol, Instant period1, Instant period2) {
        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("yahoo");
        if (props != null && !props.isEnabled()) {
            log.info("Yahoo price provider is disabled in configuration.");
            return new FetchResult(List.of(), false);
        }

        String primaryBaseUrl = props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                ? props.getBaseUrl()
                : "https://query2.finance.yahoo.com";
        String userAgent = props != null && props.getUserAgent() != null && !props.getUserAgent().isBlank()
                ? props.getUserAgent()
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";
        long timeoutMs = props != null && props.getTimeoutMs() > 0 ? props.getTimeoutMs() : 30000L;
        ZoneId zoneId = ZoneId.of(priceProperties.getTimezone() != null ? priceProperties.getTimezone() : "Asia/Kolkata");

        List<String> baseUrls = List.of(
                primaryBaseUrl,
                primaryBaseUrl.contains("query2") ? "https://query1.finance.yahoo.com" : "https://query2.finance.yahoo.com"
        );

        long p1 = period1 != null ? period1.getEpochSecond() : Instant.now().minusSeconds(86400L * 365 * 3).getEpochSecond();
        long p2 = period2 != null ? period2.getEpochSecond() : Instant.now().getEpochSecond();

        for (String baseUrl : baseUrls) {
            String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
            String url = baseUrl.replaceAll("/+$", "") + "/v8/finance/chart/" + encodedSymbol
                    + "?interval=1d&period1=" + p1 + "&period2=" + p2 + "&events=div";
            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    List<DividendEvent> events = parseDividendEventsJson(response.body(), zoneId);
                    return new FetchResult(events, true);
                } else {
                    log.warn("Yahoo Finance dividend query for symbol {} at host {} returned HTTP status {}", symbol, baseUrl, response.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch Yahoo Finance dividend events for symbol: {} at host {}", symbol, baseUrl, e);
            }
        }
        return new FetchResult(List.of(), false);
    }

    public List<DividendEvent> parseDividendEventsJson(String json, ZoneId zoneId) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode resultNode = root.path("chart").path("result");
            if (!resultNode.isArray() || resultNode.isEmpty()) {
                return List.of();
            }
            JsonNode eventsNode = resultNode.get(0).path("events").path("dividends");
            if (eventsNode.isMissingNode() || eventsNode.isNull() || !eventsNode.isObject()) {
                return List.of();
            }

            List<DividendEvent> events = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = eventsNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode divNode = entry.getValue();
                JsonNode amountNode = divNode.path("amount");
                JsonNode dateNode = divNode.path("date");

                if (!amountNode.isMissingNode() && !amountNode.isNull()) {
                    BigDecimal amount = new BigDecimal(amountNode.asText());
                    long epochSec = dateNode.isNumber() ? dateNode.asLong() : Long.parseLong(entry.getKey());
                    LocalDate exDate = Instant.ofEpochSecond(epochSec).atZone(zoneId).toLocalDate();
                    events.add(new DividendEvent(exDate, amount));
                }
            }
            return events;
        } catch (Exception e) {
            log.warn("Failed to parse Yahoo dividend events JSON", e);
            return List.of();
        }
    }
}
