package com.financeos.domain.instrument.price;

import com.financeos.domain.nps.NpsSchemeNameParser;
import com.financeos.core.observability.ExtHttp;
import com.financeos.core.observability.Ops;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The NPS Trust NAV files: the daily file with every scheme's latest NAV (tab-separated, ~263 rows) and the
 * per-scheme history file (daily since inception, newest first). Parsing tolerates a BOM, CRLF and blank lines
 * and reads columns by header name. A failed download comes back empty / FAILED; it never throws.
 */
@Component
public class NpsTrustFeedClient {

    private static final Logger log = LoggerFactory.getLogger(NpsTrustFeedClient.class);

    /** One scheme's NAV on one day from the daily file. */
    public record NavRow(String schemeCode, String schemeName, String pfmName, LocalDate navDate, BigDecimal nav) {
    }

    private final PriceProperties priceProperties;
    private final HttpClient httpClient;

    private volatile boolean loaded = false;
    private volatile List<NavRow> rows = List.of();
    private volatile Map<String, PriceQuote> quoteBySchemeCode = Map.of();

    public NpsTrustFeedClient(PriceProperties priceProperties) {
        this.priceProperties = priceProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    load();
                }
            }
        }
    }

    /** Downloads the daily file and swaps it in; keeps the previous feed when the download fails. */
    public synchronized void load() {
        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("nps");
        if (props != null && !props.isEnabled()) {
            log.info("NPS Trust price provider is disabled in configuration.");
            return;
        }
        String body = download(baseUrl(props) + "/nav-report-excel", props);
        if (body == null) {
            return;
        }
        ingest(body);
        log.info("Loaded NPS Trust feed with {} schemes.", rows.size());
    }

    /** Parses a daily file body and swaps it in as the loaded feed. */
    synchronized void ingest(String body) {
        List<NavRow> parsed = parseDaily(body);
        Map<String, PriceQuote> quotes = new HashMap<>();
        for (NavRow row : parsed) {
            quotes.putIfAbsent(row.schemeCode(), new PriceQuote(row.nav(), row.navDate()));
        }
        this.rows = List.copyOf(parsed);
        this.quoteBySchemeCode = Map.copyOf(quotes);
        this.loaded = true;
    }

    public List<NavRow> all() {
        ensureLoaded();
        return rows;
    }

    public PriceQuote getQuote(String schemeCode) {
        ensureLoaded();
        return schemeCode == null ? null : quoteBySchemeCode.get(schemeCode.trim());
    }

    /** A scheme's daily NAVs within [from, to] (inclusive), oldest first, from the history file. */
    public PriceHistoryClient.Result fetchHistory(String schemeCode, LocalDate from, LocalDate to) {
        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("nps");
        if (props != null && !props.isEnabled()) {
            return new PriceHistoryClient.Result(PriceHistoryClient.Status.FAILED, List.of());
        }
        String pfm = NpsSchemeNameParser.pfmCode(schemeCode);
        if (pfm == null) {
            return new PriceHistoryClient.Result(PriceHistoryClient.Status.FAILED, List.of());
        }
        String url = baseUrl(props) + "/scheme-wise-nav-report-excel?navcatdataxls=" + enc(pfm)
                + "&navyearselxls=all&navsubdataxls=" + enc(schemeCode.trim());
        String body = download(url, props);
        if (body == null) {
            return new PriceHistoryClient.Result(PriceHistoryClient.Status.FAILED, List.of());
        }
        List<PriceHistoryClient.DailyClose> closes = new ArrayList<>();
        for (PriceHistoryClient.DailyClose c : parseHistory(body, schemeCode.trim())) {
            if (!c.date().isBefore(from) && !c.date().isAfter(to)) {
                closes.add(c);
            }
        }
        closes.sort(java.util.Comparator.comparing(PriceHistoryClient.DailyClose::date));
        return new PriceHistoryClient.Result(PriceHistoryClient.Status.OK, closes);
    }

    // ------------------------------------------------------------------ parsing

    /** The daily file: header {@code ID, DATE OF NAV, PFM NAME, SCHEME ID, SCHEME NAME, NAV VALUE}; bad lines are skipped. */
    static List<NavRow> parseDaily(String body) {
        List<NavRow> out = new ArrayList<>();
        Map<String, Integer> cols = null;
        for (String line : lines(body)) {
            String[] cells = line.split("\t", -1);
            if (cols == null) {
                cols = header(cells);
                if (cols.containsKey("SCHEME ID") && cols.containsKey("NAV VALUE")) {
                    continue;
                }
                cols = Map.of("DATE OF NAV", 1, "PFM NAME", 2, "SCHEME ID", 3, "SCHEME NAME", 4, "NAV VALUE", 5);
            }
            String code = cell(cells, cols.get("SCHEME ID"));
            LocalDate date = date(cell(cells, cols.get("DATE OF NAV")));
            BigDecimal nav = nav(cell(cells, cols.get("NAV VALUE")));
            if (code == null || date == null || nav == null) {
                continue;
            }
            out.add(new NavRow(code, cell(cells, cols.get("SCHEME NAME")), cell(cells, cols.get("PFM NAME")), date, nav));
        }
        return out;
    }

    /**
     * The history file: header {@code ID, DATE OF NAV, PFM ID, PFM NAME, SCHEME ID, SCHEME NAME, NAV VALUE}.
     * A scheme's file also carries its predecessor's or successor's rows (e.g. SM008018 holds SM008001 before
     * Apr 2026), so only rows whose SCHEME ID is {@code schemeCode} are kept.
     */
    static List<PriceHistoryClient.DailyClose> parseHistory(String body, String schemeCode) {
        List<PriceHistoryClient.DailyClose> out = new ArrayList<>();
        Map<String, Integer> cols = null;
        for (String line : lines(body)) {
            String[] cells = line.split("\t", -1);
            if (cols == null) {
                cols = header(cells);
                if (cols.containsKey("DATE OF NAV") && cols.containsKey("NAV VALUE")) {
                    continue;
                }
                cols = Map.of("DATE OF NAV", 1, "SCHEME ID", 4, "NAV VALUE", 6);
            }
            Integer idCol = cols.get("SCHEME ID");
            String rowCode = idCol == null ? null : cell(cells, idCol);
            if (rowCode != null && !rowCode.equalsIgnoreCase(schemeCode)) {
                continue;
            }
            LocalDate date = date(cell(cells, cols.get("DATE OF NAV")));
            BigDecimal nav = nav(cell(cells, cols.get("NAV VALUE")));
            if (date == null || nav == null) {
                continue;
            }
            out.add(new PriceHistoryClient.DailyClose(date, nav));
        }
        return out;
    }

    private static List<String> lines(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        String text = body.startsWith("﻿") ? body.substring(1) : body;
        for (String line : text.split("\r?\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    private static Map<String, Integer> header(String[] cells) {
        Map<String, Integer> cols = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            cols.putIfAbsent(cells[i].trim().toUpperCase(Locale.ROOT), i);
        }
        return cols;
    }

    private static String cell(String[] cells, Integer index) {
        if (index == null || index >= cells.length) {
            return null;
        }
        String v = cells[index].trim();
        return v.isEmpty() ? null : v;
    }

    private static LocalDate date(String s) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal nav(String s) {
        try {
            BigDecimal v = s == null ? null : new BigDecimal(s);
            return v != null && v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ http

    private static String baseUrl(PriceProperties.ProviderProperties props) {
        String base = props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                ? props.getBaseUrl() : "https://npstrust.org.in";
        return base.replaceAll("/+$", "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The response body of a 200, else null. */
    private String download(String url, PriceProperties.ProviderProperties props) {
        long timeoutMs = props != null && props.getTimeoutMs() > 0 ? props.getTimeoutMs() : 30000L;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "Mozilla/5.0 (compatible; FinanceOS/1.0)")
                    .header("Accept", "text/plain, text/tab-separated-values, */*")
                    .GET()
                    .build();
            HttpResponse<String> response = ExtHttp.send(Ops.PRICE_NPS, httpClient, request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("NPS Trust download {} returned HTTP {}", url, response.statusCode());
                return null;
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("NPS Trust download {} failed: {}", url, e.getMessage());
            return null;
        }
    }
}
