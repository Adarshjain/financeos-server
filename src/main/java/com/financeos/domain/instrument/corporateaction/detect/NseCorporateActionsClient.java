package com.financeos.domain.instrument.corporateaction.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.observability.ExtHttp;
import com.financeos.core.observability.Ops;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The exchange's corporate-action list for a date window. The site only answers a client that first opened its
 * filings page (cookies), so each fetch makes that visit first. Never throws: any failure comes back as FAILED.
 */
@Component
public class NseCorporateActionsClient {

    private static final Logger log = LoggerFactory.getLogger(NseCorporateActionsClient.class);
    private static final DateTimeFormatter QUERY_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter ROW_DATE = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

    public enum Status { OK, FAILED }

    /** One feed row; {@code exDate} is null when the feed has none ("-"). */
    public record Row(String symbol, String isin, String company, String series, LocalDate exDate, String subject) {
    }

    public record Result(Status status, List<Row> rows) {
        static Result failed() {
            return new Result(Status.FAILED, List.of());
        }
    }

    private final CorporateActionsProperties properties;
    private final ObjectMapper objectMapper;

    public NseCorporateActionsClient(CorporateActionsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Every equity corporate action with an ex-date in [from, to]. One warning on failure, no retry. */
    public Result fetchWindow(LocalDate from, LocalDate to) {
        CorporateActionsProperties.Nse nse = properties.getNse();
        String base = nse.getBaseUrl().replaceAll("/+$", "");
        Duration timeout = Duration.ofMillis(nse.getTimeoutMs() > 0 ? nse.getTimeoutMs() : 30000L);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .build();
            String page = base + "/companies-listing/corporate-filings-actions";
            HttpRequest warmUp = HttpRequest.newBuilder().uri(URI.create(page))
                    .header("User-Agent", nse.getUserAgent())
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(timeout).GET().build();
            ExtHttp.send(Ops.CORP_ACTIONS_NSE, client, warmUp, HttpResponse.BodyHandlers.discarding());

            String url = base + "/api/corporates-corporateActions?index=equities&from_date=" + QUERY_DATE.format(from)
                    + "&to_date=" + QUERY_DATE.format(to);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", nse.getUserAgent())
                    .header("Accept", "application/json")
                    .header("Referer", page)
                    .timeout(timeout).GET().build();
            HttpResponse<String> response = ExtHttp.send(Ops.CORP_ACTIONS_NSE, client, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("NSE corporate actions {}..{} returned HTTP {}", from, to, response.statusCode());
                return Result.failed();
            }
            List<Row> rows = parse(response.body());
            if (rows == null) {
                log.warn("NSE corporate actions {}..{} returned a body that is not a JSON list", from, to);
                return Result.failed();
            }
            return interpret(from, to, rows);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("NSE corporate actions {}..{} interrupted", from, to);
            return Result.failed();
        } catch (Exception e) {
            log.warn("NSE corporate actions {}..{} failed: {}", from, to, e.toString());
            return Result.failed();
        }
    }

    /** A whole-market window of 3+ days always holds rows (dividends), so an empty list there means the feed was blocked. */
    Result interpret(LocalDate from, LocalDate to, List<Row> rows) {
        if (rows.isEmpty() && ChronoUnit.DAYS.between(from, to) + 1 >= 3) {
            log.warn("NSE corporate actions {}..{} returned no rows for a window of 3+ days; treating as failed", from, to);
            return Result.failed();
        }
        return new Result(Status.OK, rows);
    }

    /** The feed's JSON array as rows; null when the body is not an array. A row with no readable ex-date keeps a null date. */
    List<Row> parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                return null;
            }
            List<Row> rows = new ArrayList<>();
            for (JsonNode n : root) {
                rows.add(new Row(text(n, "symbol"), text(n, "isin"), text(n, "comp"), text(n, "series"),
                        date(text(n, "exDate")), n.path("subject").asText("")));
            }
            return rows;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(String s) {
        if (s == null || s.equals("-")) {
            return null;
        }
        try {
            return LocalDate.parse(s, ROW_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
