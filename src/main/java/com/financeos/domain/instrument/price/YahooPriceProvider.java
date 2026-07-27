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

        String baseUrl = props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                ? props.getBaseUrl()
                : "https://query1.finance.yahoo.com";
        String userAgent = props != null && props.getUserAgent() != null && !props.getUserAgent().isBlank()
                ? props.getUserAgent()
                : "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        long timeoutMs = props != null && props.getTimeoutMs() > 0 ? props.getTimeoutMs() : 30000L;
        ZoneId zoneId = ZoneId.of(priceProperties.getTimezone() != null ? priceProperties.getTimezone() : "Asia/Kolkata");

        for (Instrument inst : targetInstruments) {
            String symbol = inst.getYahooSymbol().trim();
            String url = baseUrl.replaceAll("/+$", "") + "/v8/finance/chart/" + symbol;

            try {
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", userAgent)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    log.warn("Yahoo Finance query for symbol {} returned HTTP status {}", symbol, response.statusCode());
                    continue;
                }

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

                        result.put(inst.getId(), new PriceQuote(price, asOf));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to fetch Yahoo Finance price for symbol: " + symbol, e);
            }
        }

        return result;
    }
}
