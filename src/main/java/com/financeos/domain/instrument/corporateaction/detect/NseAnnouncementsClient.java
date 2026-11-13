package com.financeos.domain.instrument.corporateaction.detect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.observability.ExtHttp;
import com.financeos.core.observability.Ops;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * A company's own notices on the exchange (the corporate-announcements feed with its attached documents). Never throws:
 * a failure comes back as a flag or an empty result. The feed only answers a client that first opened the filings page
 * (cookies); the documents sit on the archive host and need none. About one request a second, and never inside a
 * database transaction (a call there is refused).
 */
@Component
public class NseAnnouncementsClient {

    private static final Logger log = LoggerFactory.getLogger(NseAnnouncementsClient.class);
    static final int CHUNK_DAYS = 365;
    static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    static final int MAX_PDF_PAGES = 40;
    /** A document with fewer words than this has no text layer (a scan). */
    static final int SCANNED_BELOW_WORDS = 40;
    private static final long MIN_GAP_MS = 1000;
    private static final long WARM_FOR_MS = 5 * 60 * 1000;
    private static final DateTimeFormatter QUERY_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter NOTICE_DATE = DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH);

    /** One notice: {@code subject} the feed's category line, {@code summary} its text, {@code fileUrl} the attached document. */
    public record Notice(String symbol, @Nullable LocalDate date, String subject, String summary, @Nullable String fileUrl,
                         @Nullable String isin) {
    }

    /** {@code failed}: at least one request failed, so the list may be incomplete. */
    public record Listed(List<Notice> notices, boolean failed) {
    }

    /** {@code ok}: the document was fetched and opened; {@code text} is empty for a scan ({@code scanned}). */
    public record Document(boolean ok, String text, boolean scanned) {
        static Document failed() {
            return new Document(false, "", false);
        }
    }

    public record Response(int status, byte[] body, boolean tooLarge) {
    }

    /** One HTTP GET with cookies kept between calls; the body is read up to {@code maxBytes}, then reported as too large. */
    interface Transport {
        Response get(String url, Map<String, String> headers, int maxBytes) throws Exception;
    }

    private final CorporateActionsProperties properties;
    private final ObjectMapper objectMapper;
    private final Transport transport;
    private final LongSupplier nowMs;
    private final Pacer pacer;
    private final Object lock = new Object();
    private long lastRequestAt = Long.MIN_VALUE / 2;
    private long warmedAt = Long.MIN_VALUE / 2;

    /** Waits so that requests keep their gap. */
    interface Pacer {
        void sleep(long ms) throws InterruptedException;
    }

    @Autowired
    public NseAnnouncementsClient(CorporateActionsProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new HttpTransport(properties.getNse().getBodyDeadlineMs()), System::currentTimeMillis, Thread::sleep);
    }

    NseAnnouncementsClient(CorporateActionsProperties properties, ObjectMapper objectMapper, Transport transport,
                           LongSupplier nowMs, Pacer pacer) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = transport;
        this.nowMs = nowMs;
        this.pacer = pacer;
    }

    /** The notices of every symbol over [from, to], asked for in windows of at most 365 days; duplicates (same file) dropped. */
    public Listed list(Set<String> symbols, LocalDate from, LocalDate to) {
        if (inTransaction("notice list")) {
            return new Listed(List.of(), true);
        }
        if (symbols.isEmpty() || from.isAfter(to)) {
            return new Listed(List.of(), false);
        }
        Map<String, Notice> byKey = new LinkedHashMap<>();
        boolean failed = false;
        Set<String> distinct = new LinkedHashSet<>();
        symbols.stream().filter(s -> s != null && !s.isBlank()).forEach(s -> distinct.add(s.trim().toUpperCase(Locale.ROOT)));
        for (String symbol : distinct) {
            for (LocalDate start = from; !start.isAfter(to); start = start.plusDays(CHUNK_DAYS)) {
                LocalDate end = start.plusDays(CHUNK_DAYS - 1L).isAfter(to) ? to : start.plusDays(CHUNK_DAYS - 1L);
                List<Notice> chunk = fetchChunk(symbol, start, end);
                if (chunk == null) {
                    failed = true;
                    continue;
                }
                for (Notice n : chunk) {
                    byKey.putIfAbsent(n.fileUrl() != null ? n.fileUrl() : n.symbol() + "|" + n.date() + "|" + n.summary(), n);
                }
            }
        }
        return new Listed(new ArrayList<>(byKey.values()), failed);
    }

    /** Null when the request failed. */
    private List<Notice> fetchChunk(String symbol, LocalDate from, LocalDate to) {
        try {
            CorporateActionsProperties.Nse nse = properties.getNse();
            String base = nse.getBaseUrl().replaceAll("/+$", "");
            String page = base + "/companies-listing/corporate-filings-announcements";
            warmUp(page, nse.getUserAgent());
            String url = base + "/api/corporate-announcements?index=equities&symbol=" + java.net.URLEncoder.encode(symbol, java.nio.charset.StandardCharsets.UTF_8)
                    + "&from_date=" + QUERY_DATE.format(from) + "&to_date=" + QUERY_DATE.format(to);
            Response r = send(url, Map.of("User-Agent", nse.getUserAgent(), "Accept", "application/json", "Referer", page), 8 * 1024 * 1024);
            if (r.status() != 200 || r.tooLarge()) {
                log.warn("NSE announcements for {} {}..{} returned HTTP {}", symbol, from, to, r.status());
                return null;
            }
            List<Notice> out = parse(new String(r.body(), java.nio.charset.StandardCharsets.UTF_8), symbol);
            if (out == null) {
                log.warn("NSE announcements for {} {}..{} returned a body that is not a notice list", symbol, from, to);
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("NSE announcements for {} {}..{} failed: {}", symbol, from, to, e.toString());
            return null;
        }
    }

    private void warmUp(String page, String userAgent) throws Exception {
        synchronized (lock) {
            if (nowMs.getAsLong() - warmedAt < WARM_FOR_MS) {
                return;
            }
        }
        send(page, Map.of("User-Agent", userAgent, "Accept-Language", "en-US,en;q=0.9"), 1024 * 1024);
        synchronized (lock) {
            warmedAt = nowMs.getAsLong();
        }
    }

    /** The document's text from its first 40 pages. {@code ok} is false when it could not be fetched or opened. */
    public Document fetchDocument(String fileUrl) {
        if (inTransaction("notice document")) {
            return Document.failed();
        }
        try {
            String link = properties.getNse().archiveLink(fileUrl);
            if (!properties.getNse().isArchiveLink(link)) {
                log.warn("NSE notice document refused: not on the archives host");
                return Document.failed();
            }
            Response r = send(link, Map.of("User-Agent", properties.getNse().getUserAgent(), "Accept", "application/pdf,*/*"), MAX_PDF_BYTES);
            if (r.status() != 200 || r.tooLarge()) {
                log.info("NSE notice document returned HTTP {}{}", r.status(), r.tooLarge() ? " (too large)" : "");
                return Document.failed();
            }
            return extract(r.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Document.failed();
        } catch (Exception e) {
            log.info("NSE notice document failed: {}", e.toString());
            return Document.failed();
        }
    }

    /** Text of the first 40 pages; a file that is not a readable PDF is failed, one with almost no text is a scan. */
    static Document extract(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(MAX_PDF_PAGES);
            String text = stripper.getText(doc);
            boolean scanned = text.trim().split("\\s+").length < SCANNED_BELOW_WORDS;
            return new Document(true, scanned ? "" : text, scanned);
        } catch (Exception e) {
            return Document.failed();
        }
    }

    private Response send(String url, Map<String, String> headers, int maxBytes) throws Exception {
        synchronized (lock) {
            long wait = MIN_GAP_MS - (nowMs.getAsLong() - lastRequestAt);
            if (wait > 0) {
                pacer.sleep(wait);
            }
            lastRequestAt = nowMs.getAsLong();
        }
        return transport.get(url, headers, maxBytes);
    }

    private static boolean inTransaction(String what) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("NSE {} fetch refused: it must not run inside a database transaction", what);
            return true;
        }
        return false;
    }

    /** The feed's list (a bare array, or an object with {@code data}); null when the body is neither. */
    List<Notice> parse(String json, String querySymbol) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode rows = root != null && root.isObject() ? root.path("data") : root;
            if (rows == null || !rows.isArray()) {
                return null;
            }
            List<Notice> out = new ArrayList<>();
            for (JsonNode n : rows) {
                String file = text(n, "attchmntFile");
                out.add(new Notice(text(n, "symbol") == null ? querySymbol : text(n, "symbol"), date(text(n, "an_dt")),
                        n.path("desc").asText(""), n.path("attchmntText").asText(""), file, text(n, "sm_isin")));
            }
            return out;
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
        if (s == null || s.length() < 10) {
            return null;
        }
        String day = s.split(" ")[0];
        try {
            return LocalDate.parse(day, NOTICE_DATE);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(day, QUERY_DATE);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /**
     * The real transport: one cookie jar per client, timeouts, the body read up to the cap and within a total deadline. A redirect is
     * followed only to the same host (at most 3 hops): a link can never move a request to another host.
     */
    static final class HttpTransport implements Transport {
        static final long TOTAL_DEADLINE_MS = 60_000;
        private static final int MAX_HOPS = 3;
        private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "nse-body-deadline");
                    t.setDaemon(true);
                    return t;
                });

        private final long deadlineMs;
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();

        HttpTransport() {
            this(TOTAL_DEADLINE_MS);
        }

        HttpTransport(long deadlineMs) {
            this.deadlineMs = deadlineMs;
        }

        @Override
        public Response get(String url, Map<String, String> headers, int maxBytes) throws Exception {
            long deadlineAt = System.nanoTime() + deadlineMs * 1_000_000L;
            URI uri = URI.create(url);
            String host = uri.getHost();
            for (int hop = 0; ; hop++) {
                HttpRequest.Builder b = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(40)).GET();
                headers.forEach(b::header);
                HttpResponse<InputStream> response = ExtHttp.send(Ops.CORP_ACTIONS_NSE, client, b.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    try (InputStream ignored = response.body()) {
                        // closed unread
                    }
                    URI next = location == null ? null : uri.resolve(location);
                    if (next == null || hop >= MAX_HOPS || next.getHost() == null || !next.getHost().equalsIgnoreCase(host)) {
                        return new Response(status, new byte[0], false);
                    }
                    uri = next;
                    continue;
                }
                return readBody(response, status, maxBytes, deadlineAt);
            }
        }

        private static Response readBody(HttpResponse<InputStream> response, int status, int maxBytes, long deadlineAt) throws Exception {
            InputStream in = response.body();
            java.util.concurrent.ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // the reader fails on its own
                }
            }, Math.max(1, (deadlineAt - System.nanoTime()) / 1_000_000L), java.util.concurrent.TimeUnit.MILLISECONDS);
            try (in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (System.nanoTime() > deadlineAt) {
                        throw new java.io.IOException("body not read within the total deadline");
                    }
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) {
                        return new Response(status, new byte[0], true);
                    }
                }
                if (System.nanoTime() > deadlineAt) {
                    throw new java.io.IOException("body not read within the total deadline");
                }
                return new Response(status, out.toByteArray(), false);
            } finally {
                watchdog.cancel(false);
            }
        }
    }
}
