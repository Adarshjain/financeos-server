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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    public YahooPriceProvider(PriceProperties priceProperties, ObjectMapper objectMapper) {
        this.priceProperties = priceProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
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

        for (Instrument inst : targetInstruments) {
            String symbol = inst.getYahooSymbol().trim();
            PriceQuote quote = fetchQuoteForSymbol(symbol, baseUrls, userAgent, timeoutMs, zoneId);
            if (quote != null) {
                result.put(inst.getId(), quote);
            }
        }

        return result;
    }

    private PriceQuote fetchQuoteForSymbol(String symbol, List<String> baseUrls, String userAgent, long timeoutMs, ZoneId zoneId) {
        for (String baseUrl : baseUrls) {
            String encodedSymbol = java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8);
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
}
