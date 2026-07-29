package com.financeos.domain.instrument.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.instrument.dto.InstrumentCandidate;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.price.PriceProperties;
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
import java.util.concurrent.ConcurrentHashMap;

@Component
public class YahooInstrumentSearchProvider implements InstrumentSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooInstrumentSearchProvider.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final PriceProperties priceProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<CacheKey, CacheValue> cache = new ConcurrentHashMap<>();

    private record CacheKey(String query, InstrumentType type) {}
    private record CacheValue(Instant expiresAt, List<InstrumentCandidate> candidates) {}

    public YahooInstrumentSearchProvider(PriceProperties priceProperties, ObjectMapper objectMapper) {
        this.priceProperties = priceProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public boolean supports(InstrumentType type) {
        // Yahoo is used only for stocks/ETFs. Mutual funds are priced exclusively via AMFI
        // (YahooPriceProvider does not support mutual_fund), so a Yahoo-sourced MF would never
        // get a price. Exclude mutual_fund here and in mapQuoteType() to avoid unpriceable candidates.
        return type == null || type == InstrumentType.stock || type == InstrumentType.etf;
    }

    @Override
    public List<InstrumentCandidate> search(String query, InstrumentType type) {
        if (!supports(type) || query == null || query.isBlank()) {
            return List.of();
        }

        String normalizedQuery = query.trim().toLowerCase();
        CacheKey cacheKey = new CacheKey(normalizedQuery, type);
        CacheValue cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.candidates();
        }

        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("yahoo");
        if (props != null && !props.isEnabled()) {
            log.info("Yahoo price provider is disabled in configuration.");
            return List.of();
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

        List<InstrumentCandidate> results = executeSearch(normalizedQuery, type, baseUrls, userAgent, timeoutMs, zoneId);

        cache.put(cacheKey, new CacheValue(Instant.now().plus(CACHE_TTL), results));
        return results;
    }

    private List<InstrumentCandidate> executeSearch(String query, InstrumentType requestedType, List<String> baseUrls, String userAgent, long timeoutMs, ZoneId zoneId) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (String baseUrl : baseUrls) {
            String url = baseUrl.replaceAll("/+$", "") + "/v1/finance/search?q=" + encodedQuery + "&quotesCount=15&newsCount=0";
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
                    return parseQuotes(response.body(), requestedType, zoneId);
                } else {
                    log.warn("Yahoo search query for '{}' at host {} returned HTTP status {}", query, baseUrl, response.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failed to execute Yahoo search for query '{}' at host {}", query, baseUrl, e);
            }
        }
        return List.of();
    }

    private List<InstrumentCandidate> parseQuotes(String jsonBody, InstrumentType requestedType, ZoneId zoneId) {
        List<InstrumentCandidate> candidates = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            JsonNode quotesNode = root.path("quotes");
            if (quotesNode.isArray()) {
                for (JsonNode quoteNode : quotesNode) {
                    String quoteTypeStr = quoteNode.path("quoteType").asText();
                    InstrumentType mappedType = mapQuoteType(quoteTypeStr);
                    if (mappedType == null) {
                        continue;
                    }

                    if (requestedType != null && mappedType != requestedType) {
                        continue;
                    }

                    String yahooSymbol = quoteNode.path("symbol").asText();
                    if (yahooSymbol == null || yahooSymbol.isBlank()) {
                        continue;
                    }

                    String symbol = yahooSymbol.contains(".")
                            ? yahooSymbol.substring(0, yahooSymbol.indexOf('.'))
                            : yahooSymbol;

                    String name = quoteNode.hasNonNull("shortname") && !quoteNode.path("shortname").asText().isBlank()
                            ? quoteNode.path("shortname").asText()
                            : (quoteNode.hasNonNull("longname") ? quoteNode.path("longname").asText() : yahooSymbol);

                    String rawExchange = quoteNode.path("exchange").asText();
                    String exchange = mapExchange(rawExchange);

                    String currency = quoteNode.hasNonNull("currency") && !quoteNode.path("currency").asText().isBlank()
                            ? quoteNode.path("currency").asText()
                            : (yahooSymbol.endsWith(".NS") || yahooSymbol.endsWith(".BO") ? "INR" : null);

                    InstrumentCandidate.PricePreview pricePreview = null;
                    JsonNode priceNode = quoteNode.path("regularMarketPrice");
                    if (!priceNode.isMissingNode() && !priceNode.isNull() && priceNode.isNumber()) {
                        BigDecimal priceVal = new BigDecimal(priceNode.asText());
                        LocalDate asOf = LocalDate.now(zoneId);
                        pricePreview = new InstrumentCandidate.PricePreview(priceVal, asOf);
                    }

                    candidates.add(new InstrumentCandidate(
                            "YAHOO",
                            mappedType,
                            name,
                            symbol,
                            exchange,
                            null,
                            null,
                            yahooSymbol,
                            currency,
                            pricePreview,
                            null
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing Yahoo search response", e);
        }
        return candidates;
    }

    private InstrumentType mapQuoteType(String quoteTypeStr) {
        if (quoteTypeStr == null) return null;
        return switch (quoteTypeStr.toUpperCase()) {
            case "EQUITY" -> InstrumentType.stock;
            case "ETF" -> InstrumentType.etf;
            // MUTUALFUND intentionally not mapped: Yahoo MFs have no amfiCode/isin and cannot be priced here.
            default -> null;
        };
    }

    private String mapExchange(String rawExchange) {
        if (rawExchange == null || rawExchange.isBlank()) return null;
        return switch (rawExchange.toUpperCase()) {
            case "NSI" -> "NSE";
            case "BSE" -> "BSE";
            default -> rawExchange;
        };
    }
}
