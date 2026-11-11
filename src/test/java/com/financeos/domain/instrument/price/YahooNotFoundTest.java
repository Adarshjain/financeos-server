package com.financeos.domain.instrument.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentClassificationService;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentPriceRepository;
import com.financeos.domain.instrument.InstrumentRepository;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Yahoo marks an instrument not found only when its per-symbol lookup is a 404 on every host. */
class YahooNotFoundTest {

    private static final Instant T0 = Instant.parse("2026-10-20T04:30:00Z");

    private record Reply(int status, String body, String retryAfter) {
        static Reply ok(String body) { return new Reply(200, body, null); }
        static Reply status(int status) { return new Reply(status, "", null); }
        static Reply tooMany(String retryAfter) { return new Reply(429, "", retryAfter); }
    }

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, Reply> handler;
    private PriceProperties.ProviderProperties props;
    private YahooPriceProvider provider;
    private MutableClock clock;

    private static class MutableClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static String v7(String... symbols) {
        StringBuilder sb = new StringBuilder("{\"quoteResponse\":{\"result\":[");
        for (int i = 0; i < symbols.length; i++) {
            sb.append(i > 0 ? "," : "").append("{\"symbol\":\"").append(symbols[i])
                    .append("\",\"regularMarketPrice\":100,\"regularMarketTime\":1760934600,\"marketState\":\"REGULAR\"}");
        }
        return sb.append("]}}").toString();
    }

    private static final String V8 = "{\"chart\":{\"result\":[{\"meta\":{\"regularMarketPrice\":99,\"regularMarketTime\":1760934600}}]}}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            requests.add(path);
            Reply r = handler.apply(path + (query == null ? "" : "?" + query));
            if (r.retryAfter() != null) {
                ex.getResponseHeaders().add("Retry-After", r.retryAfter());
            }
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(r.status(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        props = new PriceProperties.ProviderProperties();
        props.setBaseUrl(base);
        props.setFallbackEnabled(false);
        props.setCrumbBaseUrl(base + "/prime");
        props.setCrumbFallbackUrl(base + "/prime");
        PriceProperties pp = new PriceProperties();
        pp.getProviders().put("yahoo", props);
        provider = new YahooPriceProvider(pp, new ObjectMapper());
        clock = new MutableClock();
        provider.setClock(clock);
        handler = healthy();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        AppTime.reset();
    }

    /** crumb ok, v7 returns every requested symbol, v8 returns a price. */
    private Function<String, Reply> healthy() {
        return q -> {
            if (q.startsWith("/v1/test/getcrumb")) return Reply.ok("crumb123");
            if (q.startsWith("/v7/")) {
                String symbols = q.substring(q.indexOf("symbols=") + 8, q.indexOf("&crumb"));
                return Reply.ok(v7(symbols.split(",")));
            }
            if (q.startsWith("/v8/")) return Reply.ok(V8);
            return Reply.status(200);
        };
    }

    private Function<String, Reply> with(String prefix, Reply reply) {
        Function<String, Reply> base = healthy();
        return q -> q.startsWith(prefix) ? reply : base.apply(q);
    }

    private long count(String prefix) {
        return requests.stream().filter(p -> p.startsWith(prefix)).count();
    }

    private static Instrument stock(String symbol) {
        Instrument i = new Instrument();
        i.setId(UUID.randomUUID());
        i.setType(InstrumentType.stock);
        i.setName(symbol);
        i.setYahooSymbol(symbol);
        return i;
    }

    private static final String V7_EMPTY = "{\"quoteResponse\":{\"result\":[]}}";

    private Function<String, Reply> v7EmptyAnd(Reply v8) {
        return q -> {
            if (q.startsWith("/v1/test/getcrumb")) return Reply.ok("crumb123");
            if (q.startsWith("/v7/")) return Reply.ok(V7_EMPTY);
            if (q.startsWith("/v8/")) return v8;
            return Reply.status(200);
        };
    }

    @Test
    void a404OnEveryHostOfTheSymbolLookupMarksItNotFound() {
        handler = v7EmptyAnd(Reply.status(404));
        Instrument a = stock("GONE.NS");
        assertTrue(provider.fetch(List.of(a)).isEmpty());
        assertEquals(Set.of(a.getId()), provider.takeNotFound());
        assertTrue(provider.takeNotFound().isEmpty(), "taking clears");
    }

    @Test
    void a429OnTheSymbolLookupIsNotNotFound() {
        handler = v7EmptyAnd(Reply.tooMany(null));
        assertTrue(provider.fetch(List.of(stock("GONE.NS"))).isEmpty());
        assertTrue(provider.takeNotFound().isEmpty());
    }

    @Test
    void a5xxOnTheSymbolLookupIsNotNotFound() {
        handler = v7EmptyAnd(Reply.status(503));
        assertTrue(provider.fetch(List.of(stock("GONE.NS"))).isEmpty());
        assertTrue(provider.takeNotFound().isEmpty());
    }

    @Test
    void anErrorOnTheSymbolLookupIsNotNotFound() {
        handler = q -> {
            if (q.startsWith("/v8/")) throw new RuntimeException("boom");
            return v7EmptyAnd(Reply.status(404)).apply(q);
        };
        assertTrue(provider.fetch(List.of(stock("GONE.NS"))).isEmpty());
        assertTrue(provider.takeNotFound().isEmpty());
    }

    @Test
    void aPricedSymbolIsNotNotFound() {
        Instrument a = stock("INFY.NS");
        assertEquals(1, provider.fetch(List.of(a)).size());
        assertTrue(provider.takeNotFound().isEmpty());
    }

    @Test
    void liveFetchNeverMarksAnything() {
        handler = v7EmptyAnd(Reply.status(404));
        assertTrue(provider.fetchLive(List.of(stock("GONE.NS"))).isEmpty());
        assertTrue(provider.takeNotFound().isEmpty());
    }
}
