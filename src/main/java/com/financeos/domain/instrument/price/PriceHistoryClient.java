package com.financeos.domain.instrument.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily closing-price history from Yahoo (stocks/ETFs, one chart request) and mfapi.in (mutual
 * funds, the scheme's whole NAV history). Never throws: a failure or a rate limit comes back as a
 * status so the caller can skip quietly.
 */
@Component
public class PriceHistoryClient {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryClient.class);
    private static final DateTimeFormatter MFAPI_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public record DailyClose(LocalDate date, BigDecimal close) {}

    public enum Status { OK, RATE_LIMITED, FAILED }

    public record Result(Status status, List<DailyClose> closes) {
        static Result of(Status status) {
            return new Result(status, List.of());
        }
    }

    private final PriceProperties priceProperties;
    private final YahooPriceProvider yahooPriceProvider;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PriceHistoryClient(PriceProperties priceProperties, YahooPriceProvider yahooPriceProvider,
                              ObjectMapper objectMapper) {
        this.priceProperties = priceProperties;
        this.yahooPriceProvider = yahooPriceProvider;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Daily closes of a Yahoo symbol over [from, to] (inclusive), oldest first. One request, no retry. */
    public Result fetchYahoo(String symbol, LocalDate from, LocalDate to) {
        if (yahooPriceProvider.isRateLimited()) {
            log.info("Yahoo history for {} skipped: rate limited", symbol);
            return Result.of(Status.RATE_LIMITED);
        }
        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("yahoo");
        if (props != null && !props.isEnabled()) {
            return Result.of(Status.FAILED);
        }
        ZoneId zone = zone();
        long p1 = from.atStartOfDay(zone).toEpochSecond();
        long p2 = to.plusDays(1).atStartOfDay(zone).toEpochSecond();
        String base = YahooHosts.getCandidateHosts(props).get(0).replaceAll("/+$", "");
        String url = base + "/v8/finance/chart/" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "?interval=1d&period1=" + p1 + "&period2=" + p2;
        String userAgent = props != null && props.getUserAgent() != null && !props.getUserAgent().isBlank()
                ? props.getUserAgent() : "Mozilla/5.0";
        long timeoutMs = props != null && props.getTimeoutMs() > 0 ? props.getTimeoutMs() : 30000L;
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                yahooPriceProvider.noteRateLimited(response);
                return Result.of(Status.RATE_LIMITED);
            }
            if (response.statusCode() != 200) {
                log.info("Yahoo history for {} returned HTTP {}", symbol, response.statusCode());
                return Result.of(Status.FAILED);
            }
            return new Result(Status.OK, parseYahoo(response.body(), zone));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.of(Status.FAILED);
        } catch (Exception e) {
            log.info("Yahoo history for {} failed: {}", symbol, e.getMessage());
            return Result.of(Status.FAILED);
        }
    }

    /** Daily closes of an AMFI scheme from mfapi.in within [from, to] (inclusive), oldest first. */
    public Result fetchMutualFund(String schemeCode, LocalDate from, LocalDate to) {
        PriceProperties.Mfapi props = priceProperties.getMfapi();
        String base = props.getBaseUrl().replaceAll("/+$", "");
        String url = base + "/mf/" + URLEncoder.encode(schemeCode, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofMillis(props.getTimeoutMs() > 0 ? props.getTimeoutMs() : 30000L))
                    .GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                return Result.of(Status.RATE_LIMITED);
            }
            if (response.statusCode() != 200) {
                log.info("mfapi history for scheme {} returned HTTP {}", schemeCode, response.statusCode());
                return Result.of(Status.FAILED);
            }
            return new Result(Status.OK, parseMfapi(response.body(), from, to));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.of(Status.FAILED);
        } catch (Exception e) {
            log.info("mfapi history for scheme {} failed: {}", schemeCode, e.getMessage());
            return Result.of(Status.FAILED);
        }
    }

    /** Chart JSON: {@code result[0].timestamp[]} with {@code indicators.quote[0].close[]}; null closes (holidays) dropped. */
    List<DailyClose> parseYahoo(String json, ZoneId fallbackZone) {
        try {
            JsonNode result = objectMapper.readTree(json).path("chart").path("result");
            if (!result.isArray() || result.isEmpty()) {
                return List.of();
            }
            JsonNode first = result.get(0);
            JsonNode timestamps = first.path("timestamp");
            JsonNode closes = first.path("indicators").path("quote").path(0).path("close");
            if (!timestamps.isArray() || !closes.isArray()) {
                return List.of();
            }
            ZoneId zone = fallbackZone;
            JsonNode offset = first.path("meta").path("gmtoffset");
            if (offset.isNumber()) {
                zone = java.time.ZoneOffset.ofTotalSeconds(offset.asInt());
            }
            List<DailyClose> out = new ArrayList<>();
            for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
                JsonNode c = closes.get(i);
                if (c == null || c.isNull() || !c.isNumber() || !timestamps.get(i).isNumber()) {
                    continue;
                }
                BigDecimal close = BigDecimal.valueOf(c.asDouble()).setScale(4, RoundingMode.HALF_UP);
                if (close.signum() <= 0) {
                    continue;
                }
                LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong()).atZone(zone).toLocalDate();
                out.add(new DailyClose(date, close));
            }
            return out;
        } catch (Exception e) {
            log.info("Could not parse Yahoo history: {}", e.getMessage());
            return List.of();
        }
    }

    /** mfapi JSON {@code {meta, data:[{date:"dd-MM-yyyy", nav:"104.4153"}]}} (newest first), kept within [from, to], oldest first. */
    List<DailyClose> parseMfapi(String json, LocalDate from, LocalDate to) {
        try {
            JsonNode data = objectMapper.readTree(json).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<DailyClose> out = new ArrayList<>();
            for (JsonNode row : data) {
                try {
                    LocalDate date = LocalDate.parse(row.path("date").asText(), MFAPI_DATE);
                    if (date.isBefore(from) || date.isAfter(to)) {
                        continue;
                    }
                    BigDecimal nav = new BigDecimal(row.path("nav").asText().trim()).setScale(4, RoundingMode.HALF_UP);
                    if (nav.signum() > 0) {
                        out.add(new DailyClose(date, nav));
                    }
                } catch (Exception skip) {
                    // one bad row (unparseable date or "N.A." nav) must not drop the rest
                }
            }
            out.sort(java.util.Comparator.comparing(DailyClose::date));
            return out;
        } catch (Exception e) {
            log.info("Could not parse mfapi history: {}", e.getMessage());
            return List.of();
        }
    }

    private ZoneId zone() {
        return ZoneId.of(priceProperties.getTimezone() != null ? priceProperties.getTimezone() : "Asia/Kolkata");
    }
}
