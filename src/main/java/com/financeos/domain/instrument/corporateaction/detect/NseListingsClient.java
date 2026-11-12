package com.financeos.domain.instrument.corporateaction.detect;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The exchange's equity master list (every listed company with its listing date), kept in memory for 24 hours.
 * Never blocks: a refresh runs in the background; on a failure the last good list is kept and the fetch is not retried for an hour.
 */
@Component
public class NseListingsClient {

    private static final Logger log = LoggerFactory.getLogger(NseListingsClient.class);
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration RETRY_AFTER = Duration.ofHours(1);
    private static final DateTimeFormatter LISTING_DATE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("d-MMM-yyyy").toFormatter(Locale.ENGLISH);

    /** One listed company; {@code isin} is null when the list has none. */
    public record Listing(String symbol, String name, LocalDate listedOn, String isin) {
    }

    /** Reads the CSV body; throws on any failure. */
    @FunctionalInterface
    interface Fetcher {
        String fetch() throws Exception;
    }

    private final Clock clock;
    private final Fetcher fetcher;
    private final Executor executor;
    private final Object lock = new Object();
    private List<Listing> cached;
    private Instant fetchedAt;
    private Instant nextAttemptAt = Instant.MIN;
    private boolean inFlight;

    @Autowired
    public NseListingsClient(CorporateActionsProperties properties) {
        this(Clock.systemUTC(), http(properties), Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nse-listings-refresh");
            t.setDaemon(true);
            return t;
        }));
    }

    NseListingsClient(Clock clock, Fetcher fetcher, Executor executor) {
        this.clock = clock;
        this.fetcher = fetcher;
        this.executor = executor;
    }

    /** Fills the cache in the background once the app is up; never delays startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        listings();
    }

    /**
     * The cached list (empty until the first good fetch); never waits on the network. A missing or stale cache
     * starts one background refresh, unless the last attempt failed less than an hour ago.
     */
    public List<Listing> listings() {
        synchronized (lock) {
            Instant now = clock.instant();
            boolean fresh = cached != null && now.isBefore(fetchedAt.plus(TTL));
            if (!fresh && !inFlight && !now.isBefore(nextAttemptAt)) {
                inFlight = true;
                try {
                    executor.execute(this::refresh);
                } catch (RuntimeException e) {
                    inFlight = false;
                    nextAttemptAt = now.plus(RETRY_AFTER);
                    log.warn("NSE equity list refresh could not start: {}", e.toString());
                }
            }
            return cached == null ? List.of() : cached;
        }
    }

    private void refresh() {
        List<Listing> fresh = null;
        try {
            fresh = parse(fetcher.fetch());
            if (fresh.isEmpty()) {
                log.warn("NSE equity list had no readable rows");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("NSE equity list fetch interrupted");
        } catch (Exception e) {
            log.warn("NSE equity list fetch failed: {}", e.toString());
        }
        synchronized (lock) {
            Instant now = clock.instant();
            if (fresh == null || fresh.isEmpty()) {
                nextAttemptAt = now.plus(RETRY_AFTER);
            } else {
                cached = List.copyOf(fresh);
                fetchedAt = now;
            }
            inFlight = false;
        }
    }

    private static Fetcher http(CorporateActionsProperties properties) {
        return () -> {
            CorporateActionsProperties.Nse nse = properties.getNse();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(nse.getListingsUrl()))
                    .header("User-Agent", nse.getUserAgent())
                    .header("Accept", "text/csv,*/*")
                    .timeout(TOTAL_TIMEOUT).GET().build();
            CompletableFuture<HttpResponse<String>> call = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = call.get(TOTAL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                return response.body();
            } catch (TimeoutException e) {
                call.cancel(true);
                throw e;
            }
        };
    }

    /** The CSV as listings: header names are trimmed and matched case-insensitively; a row without symbol, name or a readable date is skipped. */
    static List<Listing> parse(String csv) {
        List<Listing> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        if (csv.startsWith("\uFEFF")) {
            csv = csv.substring(1);
        }
        Map<String, Integer> cols = null;
        for (String line : csv.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            List<String> f = split(line);
            if (cols == null) {
                cols = new HashMap<>();
                for (int i = 0; i < f.size(); i++) {
                    cols.put(f.get(i).trim().toUpperCase(Locale.ROOT), i);
                }
                if (!cols.containsKey("SYMBOL") || !cols.containsKey("DATE OF LISTING")) {
                    return out;
                }
                continue;
            }
            String symbol = at(f, cols.get("SYMBOL"));
            String name = at(f, cols.get("NAME OF COMPANY"));
            LocalDate listed = date(at(f, cols.get("DATE OF LISTING")));
            if (symbol == null || name == null || listed == null) {
                continue;
            }
            out.add(new Listing(symbol, name, listed, at(f, cols.get("ISIN NUMBER"))));
        }
        return out;
    }

    private static String at(List<String> f, Integer i) {
        if (i == null || i >= f.size()) {
            return null;
        }
        String s = f.get(i).trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate date(String s) {
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s, LISTING_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** One CSV line split on commas outside quotes; a doubled quote inside quotes is one quote. */
    private static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
