package com.financeos.domain.instrument.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
public class YahooPriceProvider implements PriceProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooPriceProvider.class);

    private final PriceProperties priceProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Object crumbLock = new Object();
    private String cachedCrumb = null;
    private Instant crumbFetchedAt = null;
    private static final Duration CRUMB_TTL = Duration.ofMinutes(15);

    public YahooPriceProvider(PriceProperties priceProperties, ObjectMapper objectMapper) {
        this.priceProperties = priceProperties;
        this.objectMapper = objectMapper;
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .build();
    }

    @Override
    public PriceSource source() {
        return PriceSource.YAHOO;
    }

    @Override
    public boolean supports(Instrument instrument) {
        return instrument != null && (instrument.getType() == InstrumentType.stock || instrument.getType() == InstrumentType.etf);
    }

    @Override
    public Map<UUID, PriceQuote> fetch(List<Instrument> instruments) {
        Map<UUID, PriceQuote> result = new HashMap<>();
        List<Instrument> targetInstruments = instruments.stream()
                .filter(this::supports)
                .filter(inst -> inst.getYahooSymbol() != null && !inst.getYahooSymbol().isBlank())
                .toList();

        if (targetInstruments.isEmpty()) {
            return result;
        }

        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("yahoo");
        if (props != null && !props.isEnabled()) {
            log.info("Yahoo price provider is disabled in configuration.");
            return result;
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

        boolean batchEnabled = props == null || props.isBatchEnabled();
        int batchSize = (props != null && props.getBatchSize() > 0) ? props.getBatchSize() : 50;

        if (batchEnabled) {
            String crumb = getOrFetchCrumb(baseUrls, userAgent, timeoutMs, false);
            if (crumb != null) {
                List<List<Instrument>> chunks = new ArrayList<>();
                for (int i = 0; i < targetInstruments.size(); i += batchSize) {
                    chunks.add(targetInstruments.subList(i, Math.min(i + batchSize, targetInstruments.size())));
                }

                for (int i = 0; i < chunks.size(); i++) {
                    if (i > 0) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    List<Instrument> chunk = chunks.get(i);
                    List<String> rawSymbols = chunk.stream()
                            .map(inst -> inst.getYahooSymbol().trim())
                            .distinct()
                            .toList();

                    String joinedSymbols = String.join(",", rawSymbols.stream()
                            .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8))
                            .toList());

                    Map<String, PriceQuote> batchQuotes = null;
                    try {
                        batchQuotes = fetchBatchChunk(joinedSymbols, crumb, baseUrls, userAgent, timeoutMs, zoneId);
                    } catch (CrumbExpiredException e) {
                        log.info("Yahoo crumb expired (401), refreshing crumb once...");
                        crumb = getOrFetchCrumb(baseUrls, userAgent, timeoutMs, true);
                        if (crumb != null) {
                            try {
                                batchQuotes = fetchBatchChunk(joinedSymbols, crumb, baseUrls, userAgent, timeoutMs, zoneId);
                            } catch (Exception ex) {
                                log.warn("Retry Yahoo batch chunk query failed after crumb refresh", ex);
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Yahoo batch chunk query failed", e);
                    }

                    if (batchQuotes != null && !batchQuotes.isEmpty()) {
                        for (Instrument inst : chunk) {
                            String lowerSymbol = inst.getYahooSymbol().trim().toLowerCase(Locale.ROOT);
                            PriceQuote quote = batchQuotes.get(lowerSymbol);
                            if (quote != null) {
                                result.put(inst.getId(), quote);
                            }
                        }
                    }
                }
            } else {
                log.warn("Yahoo crumb bootstrap failed; proceeding with per-symbol fallback for all tickers.");
            }
        }

        // Graceful fallback to per-symbol endpoint for any instruments missing a quote
        for (Instrument inst : targetInstruments) {
            if (!result.containsKey(inst.getId())) {
                String symbol = inst.getYahooSymbol().trim();
                PriceQuote quote = fetchQuoteForSymbol(symbol, baseUrls, userAgent, timeoutMs, zoneId);
                if (quote != null) {
                    result.put(inst.getId(), quote);
                }
            }
        }

        return result;
    }

    private String getOrFetchCrumb(List<String> baseUrls, String userAgent, long timeoutMs, boolean forceRefresh) {
        synchronized (crumbLock) {
            if (!forceRefresh && cachedCrumb != null && crumbFetchedAt != null) {
                if (Duration.between(crumbFetchedAt, Instant.now()).compareTo(CRUMB_TTL) < 0) {
                    return cachedCrumb;
                }
            }
            cachedCrumb = bootstrapCrumb(baseUrls, userAgent, timeoutMs);
            crumbFetchedAt = Instant.now();
            return cachedCrumb;
        }
    }

    private String bootstrapCrumb(List<String> baseUrls, String userAgent, long timeoutMs) {
        // 1. Priming GET to seed cookies
        try {
            HttpRequest primeReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://fc.yahoo.com"))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            httpClient.send(primeReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            try {
                HttpRequest primeReq2 = HttpRequest.newBuilder()
                        .uri(URI.create("https://finance.yahoo.com"))
                        .header("User-Agent", userAgent)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET()
                        .build();
                httpClient.send(primeReq2, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ex) {
                log.warn("Priming request to Yahoo failed: {}", ex.getMessage());
            }
        }

        // 2. GET {baseUrl}/v1/test/getcrumb
        for (String baseUrl : baseUrls) {
            String crumbUrl = baseUrl.replaceAll("/+$", "") + "/v1/test/getcrumb";
            try {
                HttpRequest crumbReq = HttpRequest.newBuilder()
                        .uri(URI.create(crumbUrl))
                        .header("User-Agent", userAgent)
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(crumbReq, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body() != null) {
                    String body = response.body().trim();
                    if (!body.isBlank() && !body.contains("Too Many Requests") && !body.toLowerCase().contains("<html")) {
                        log.debug("Successfully bootstrapped Yahoo crumb from host {}", baseUrl);
                        return body;
                    }
                } else {
                    log.warn("Yahoo getcrumb at host {} returned HTTP status {}", baseUrl, response.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch Yahoo crumb from host {}", baseUrl, e);
            }
        }
        log.warn("Yahoo crumb bootstrap failed on all hosts.");
        return null;
    }

    private Map<String, PriceQuote> fetchBatchChunk(
            String joinedSymbols,
            String crumb,
            List<String> baseUrls,
            String userAgent,
            long timeoutMs,
            ZoneId zoneId) throws CrumbExpiredException {

        String encodedCrumb = URLEncoder.encode(crumb, StandardCharsets.UTF_8);

        for (String baseUrl : baseUrls) {
            String url = baseUrl.replaceAll("/+$", "") + "/v7/finance/quote?symbols=" + joinedSymbols + "&crumb=" + encodedCrumb;
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
                    return parseBatchQuoteResponse(response.body(), zoneId);
                } else if (response.statusCode() == 401) {
                    log.warn("Yahoo v7 quote returned 401 Invalid Crumb at host {}", baseUrl);
                    throw new CrumbExpiredException("401 Invalid Crumb");
                } else {
                    log.warn("Yahoo Finance batch query at host {} returned HTTP status {}", baseUrl, response.statusCode());
                }
            } catch (CrumbExpiredException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to fetch Yahoo Finance batch prices at host {}", baseUrl, e);
            }
        }
        return null;
    }

    private Map<String, PriceQuote> parseBatchQuoteResponse(String body, ZoneId zoneId) {
        Map<String, PriceQuote> quotes = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode quoteResponse = root.path("quoteResponse");
            JsonNode resultNode = quoteResponse.path("result");
            if (resultNode.isArray()) {
                for (JsonNode item : resultNode) {
                    JsonNode symbolNode = item.path("symbol");
                    JsonNode priceNode = item.path("regularMarketPrice");
                    JsonNode timeNode = item.path("regularMarketTime");

                    if (!symbolNode.isMissingNode() && !symbolNode.isNull() &&
                        !priceNode.isMissingNode() && !priceNode.isNull()) {

                        String symbol = symbolNode.asText().trim().toLowerCase(Locale.ROOT);
                        try {
                            BigDecimal price = new BigDecimal(priceNode.asText());
                            LocalDate asOf;
                            if (!timeNode.isMissingNode() && !timeNode.isNull() && timeNode.isNumber()) {
                                long epochSeconds = timeNode.asLong();
                                asOf = Instant.ofEpochSecond(epochSeconds).atZone(zoneId).toLocalDate();
                            } else {
                                asOf = LocalDate.now(zoneId);
                            }
                            quotes.put(symbol, new PriceQuote(price, asOf));
                        } catch (NumberFormatException e) {
                            log.warn("Invalid regularMarketPrice for symbol {}: {}", symbolNode.asText(), priceNode.asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Yahoo batch quote JSON response", e);
        }
        return quotes;
    }

    private PriceQuote fetchQuoteForSymbol(String symbol, List<String> baseUrls, String userAgent, long timeoutMs, ZoneId zoneId) {
        for (String baseUrl : baseUrls) {
            String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
            String url = baseUrl.replaceAll("/+$", "") + "/v8/finance/chart/" + encodedSymbol + "?interval=1d&range=1d";
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
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode resultNode = root.path("chart").path("result");
                    if (resultNode.isArray() && !resultNode.isEmpty()) {
                        JsonNode meta = resultNode.get(0).path("meta");
                        JsonNode priceNode = meta.path("regularMarketPrice");
                        JsonNode timeNode = meta.path("regularMarketTime");

                        if (!priceNode.isMissingNode() && !priceNode.isNull()) {
                            BigDecimal price = new BigDecimal(priceNode.asText());
                            LocalDate asOf;
                            if (!timeNode.isMissingNode() && !timeNode.isNull() && timeNode.isNumber()) {
                                long epochSeconds = timeNode.asLong();
                                asOf = Instant.ofEpochSecond(epochSeconds).atZone(zoneId).toLocalDate();
                            } else {
                                asOf = LocalDate.now(zoneId);
                            }
                            return new PriceQuote(price, asOf);
                        }
                    }
                } else {
                    log.warn("Yahoo Finance query for symbol {} at host {} returned HTTP status {}", symbol, baseUrl, response.statusCode());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch Yahoo Finance price for symbol: {} at host {}", symbol, baseUrl, e);
            }
        }
        return null;
    }

    private static class CrumbExpiredException extends Exception {
        public CrumbExpiredException(String message) {
            super(message);
        }
    }
}
