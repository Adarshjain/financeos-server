package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.corporateaction.detect.SchemeNoticeReader.NameChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The exchange's list of company renames (old name, new name, the symbol it trades under now), kept in memory for
 * 24 hours like the equity list. Never blocks: a refresh runs in the background; on a failure the last good list is
 * kept and the fetch is not retried for an hour.
 */
@Component
public class NseNameChangesClient {

    private static final Logger log = LoggerFactory.getLogger(NseNameChangesClient.class);
    private static final Duration TTL = Duration.ofHours(24);
    private static final Duration RETRY_AFTER = Duration.ofHours(1);

    /** Reads the CSV body; throws on any failure. */
    @FunctionalInterface
    interface Fetcher {
        String fetch() throws Exception;
    }

    private final Clock clock;
    private final Fetcher fetcher;
    private final Executor executor;
    private final Object lock = new Object();
    private List<NameChange> cached;
    private Instant fetchedAt;
    private Instant nextAttemptAt = Instant.MIN;
    private boolean inFlight;

    @Autowired
    public NseNameChangesClient(CorporateActionsProperties properties) {
        this(Clock.systemUTC(), http(properties), Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nse-namechanges-refresh");
            t.setDaemon(true);
            return t;
        }));
    }

    NseNameChangesClient(Clock clock, Fetcher fetcher, Executor executor) {
        this.clock = clock;
        this.fetcher = fetcher;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        nameChanges();
    }

    /** The cached renames (empty until the first good fetch); never waits on the network. */
    public List<NameChange> nameChanges() {
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
                    log.warn("NSE name change list refresh could not start: {}", e.toString());
                }
            }
            return cached == null ? List.of() : cached;
        }
    }

    private void refresh() {
        List<NameChange> fresh = null;
        try {
            fresh = parse(fetcher.fetch());
            if (fresh.isEmpty()) {
                log.warn("NSE name change list had no readable rows");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("NSE name change list fetch failed: {}", e.toString());
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
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(nse.getNameChangesUrl()))
                    .header("User-Agent", nse.getUserAgent()).header("Accept", "text/csv,*/*")
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new java.io.IOException("HTTP " + response.statusCode());
            }
            return response.body();
        };
    }

    /** The CSV as renames: header names matched case-insensitively; a row missing the symbol or a name is skipped. */
    static List<NameChange> parse(String csv) {
        List<NameChange> out = new ArrayList<>();
        if (csv == null) {
            return out;
        }
        if (csv.startsWith("﻿")) {
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
                if (!cols.containsKey("SYMBOL") || !cols.containsKey("OLD NAME OF THE COMPANY") || !cols.containsKey("NEW NAME OF THE COMPANY")) {
                    return out;
                }
                continue;
            }
            String symbol = at(f, cols.get("SYMBOL"));
            String oldName = at(f, cols.get("OLD NAME OF THE COMPANY"));
            String newName = at(f, cols.get("NEW NAME OF THE COMPANY"));
            if (symbol != null && oldName != null && newName != null) {
                out.add(new NameChange(symbol, oldName, newName));
            }
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
