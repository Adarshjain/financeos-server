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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Yahoo live (v7 only) vs end-of-day (v7 + v8 fallback) fetches and the HTTP 429 cooldown, against a local stub server. */
class YahooRateLimitTest {

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

    private void assertCooldown(Duration expected) {
        assertEquals(clock.now.plus(expected), provider.rateLimitedUntil());
    }

    @Test
    void liveV7NonOkMakesNoV8RequestAndReturnsEmpty() {
        handler = with("/v7/", Reply.status(500));
        assertTrue(provider.fetchLive(List.of(stock("INFY.NS"))).isEmpty());
        assertEquals(0, count("/v8/"));
        assertEquals(1, count("/v7/"));
    }

    @Test
    void liveV7ExceptionMakesNoV8RequestAndReturnsEmpty() {
        handler = q -> {
            if (q.startsWith("/v7/")) throw new RuntimeException("boom");
            return healthy().apply(q);
        };
        assertTrue(provider.fetchLive(List.of(stock("INFY.NS"))).isEmpty());
        assertEquals(0, count("/v8/"));
    }

    @Test
    void liveCrumbUnavailableMakesNoV8OrV7RequestAndReturnsEmpty() {
        handler = with("/v1/test/getcrumb", Reply.status(500));
        assertTrue(provider.fetchLive(List.of(stock("INFY.NS"))).isEmpty());
        assertEquals(0, count("/v8/"));
        assertEquals(0, count("/v7/"));
    }

    @Test
    void liveHealthyReturnsV7Quotes() {
        Instrument a = stock("INFY.NS");
        Map<UUID, PriceQuote> out = provider.fetchLive(List.of(a));
        assertEquals(1, out.size());
        assertEquals(0, new BigDecimal("100").compareTo(out.get(a.getId()).close()));
        assertEquals(0, count("/v8/"));
    }

    @Test
    void endOfDayFetchStillFallsBackToV8WhenV7Fails() {
        handler = with("/v7/", Reply.status(500));
        Instrument a = stock("INFY.NS");
        Map<UUID, PriceQuote> out = provider.fetch(List.of(a));
        assertEquals(1, out.size());
        assertEquals(0, new BigDecimal("99").compareTo(out.get(a.getId()).close()));
        assertEquals(1, count("/v8/"));
    }

    @Test
    void retryAfterSecondsSetsCooldownAndLiveFetchesPauseThenResume() {
        handler = with("/v7/", Reply.tooMany("120"));
        assertTrue(provider.fetchLive(List.of(stock("INFY.NS"))).isEmpty());
        assertCooldown(Duration.ofSeconds(120));
        assertEquals(0, count("/v8/"));

        handler = healthy();
        int before = requests.size();
        clock.now = T0.plusSeconds(119);
        assertTrue(provider.fetchLive(List.of(stock("INFY.NS"))).isEmpty());
        assertEquals(before, requests.size(), "no HTTP calls during the cooldown");

        clock.now = T0.plusSeconds(120);
        assertEquals(1, provider.fetchLive(List.of(stock("INFY.NS"))).size());
        assertTrue(requests.size() > before);
    }

    @Test
    void retryAfterHttpDateSetsCooldownUntilThatDate() {
        Instant until = T0.plusSeconds(600);
        handler = with("/v7/", Reply.tooMany(DateTimeFormatter.RFC_1123_DATE_TIME.format(until.atOffset(ZoneOffset.UTC))));
        provider.fetchLive(List.of(stock("INFY.NS")));
        assertEquals(until, provider.rateLimitedUntil());
    }

    @Test
    void missingRetryAfterUsesConfiguredDefault() {
        props.setRateLimitCooldown(Duration.ofMinutes(7));
        handler = with("/v7/", Reply.tooMany(null));
        provider.fetchLive(List.of(stock("INFY.NS")));
        assertCooldown(Duration.ofMinutes(7));
    }

    @Test
    void unsetConfiguredDefaultIs15Minutes() {
        assertEquals(Duration.ofMinutes(15), new PriceProperties.ProviderProperties().getRateLimitCooldown());
        handler = with("/v7/", Reply.tooMany(null));
        provider.fetchLive(List.of(stock("INFY.NS")));
        assertCooldown(Duration.ofMinutes(15));
    }

    @Test
    void retryAfterAboveAnHourIsCappedAtOneHour() {
        handler = with("/v7/", Reply.tooMany("999999"));
        provider.fetchLive(List.of(stock("INFY.NS")));
        assertCooldown(Duration.ofMinutes(60));
    }

    @Test
    void zeroNegativeOrGarbageRetryAfterUsesTheDefault() {
        for (String bad : new String[]{"0", "-5", "soon", "Wed, 21 Oct 2015 07:28:00 GMT"}) {
            handler = with("/v7/", Reply.tooMany(bad));
            provider.fetchLive(List.of(stock("INFY.NS")));
            assertCooldown(Duration.ofMinutes(15));
            clock.now = clock.now.plus(Duration.ofHours(2)); // past the cooldown so the next loop calls again
        }
    }

    @Test
    void parseRetryAfterHandlesTheForms() {
        assertEquals(Duration.ofSeconds(30), YahooPriceProvider.parseRetryAfter(" 30 ", T0));
        assertNull(YahooPriceProvider.parseRetryAfter(null, T0));
        assertNull(YahooPriceProvider.parseRetryAfter("", T0));
        assertNull(YahooPriceProvider.parseRetryAfter("1.5", T0));
    }

    @Test
    void a429MidBatchStopsTheRemainingChunks() {
        props.setBatchSize(2);
        List<Instrument> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(stock("S" + i + ".NS"));
        Function<String, Reply> base = healthy();
        handler = q -> {
            if (q.startsWith("/v7/") && count("/v7/") >= 2) return Reply.tooMany("60");
            return base.apply(q);
        };
        Map<UUID, PriceQuote> out = provider.fetchLive(five);
        assertEquals(2, count("/v7/"), "chunk 1 ok, chunk 2 gets 429, chunk 3 never sent");
        assertEquals(2, out.size());
        assertEquals(0, count("/v8/"));
    }

    @Test
    void a429WhileRefreshingAnExpiredCrumbStopsTheRemainingChunks() {
        props.setBatchSize(2);
        List<Instrument> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(stock("S" + i + ".NS"));
        Function<String, Reply> base = healthy();
        handler = q -> {
            if (q.startsWith("/v7/")) return Reply.status(401);
            if (q.startsWith("/v1/test/getcrumb") && count("/v1/test/getcrumb") >= 2) return Reply.tooMany("60");
            return base.apply(q);
        };
        assertTrue(provider.fetchLive(five).isEmpty());
        assertEquals(1, count("/v7/"), "chunk 1 gets 401, the crumb refresh gets 429, chunks 2-3 never sent");
        assertCooldown(Duration.ofSeconds(60));
        assertEquals(0, count("/v8/"));
    }

    @Test
    void aLater429NeverShortensAnActiveCooldown() {
        handler = with("/v7/", Reply.tooMany("3600"));
        provider.fetchLive(List.of(stock("INFY.NS")));
        assertCooldown(Duration.ofHours(1));
        handler = with("/v7/", Reply.tooMany(null));
        provider.fetch(List.of(stock("INFY.NS")));
        assertCooldown(Duration.ofHours(1));
    }

    @Test
    void a429OnGetCrumbStartsTheCooldown() {
        handler = with("/v1/test/getcrumb", Reply.tooMany("90"));
        assertTrue(provider.fetchLive(List.of(stock("INFY.NS"))).isEmpty());
        assertCooldown(Duration.ofSeconds(90));
        assertEquals(0, count("/v7/"));
    }

    @Test
    void a429OnThePrimingRequestStartsTheCooldown() {
        handler = with("/prime", Reply.tooMany("45"));
        assertTrue(provider.fetchLive(List.of(stock("INFY.NS"))).isEmpty());
        assertCooldown(Duration.ofSeconds(45));
        assertEquals(0, count("/v1/test/getcrumb"));
        assertEquals(0, count("/v7/"));
    }

    @Test
    void endOfDayFetchIsNotSkippedDuringACooldownAndGoesToV8After429() {
        handler = with("/v7/", Reply.tooMany("300"));
        provider.fetchLive(List.of(stock("INFY.NS"))); // starts the cooldown
        long v7Before = count("/v7/");

        props.setBatchSize(1);
        List<Instrument> two = List.of(stock("A.NS"), stock("B.NS"));
        Map<UUID, PriceQuote> out = provider.fetch(two);
        assertEquals(1, count("/v7/") - v7Before, "tried once, then skipped the remaining v7 chunk");
        assertEquals(2, count("/v8/"), "every instrument falls back to v8");
        assertEquals(2, out.size());
        assertCooldown(Duration.ofSeconds(300));
    }

    @Test
    void refreshIntradayDuringCooldownWritesNothingAndLeavesTheTodayRowAlone() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        AppTime.useClock(Clock.fixed(LocalDateTime.parse("2026-10-20T10:00:00").atZone(ist).toInstant(), ist));
        clock.now = T0; // the provider's clock: 10:00 IST
        handler = with("/v7/", Reply.tooMany("600"));

        Instrument inst = stock("INFY.NS");
        InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
        InstrumentPriceRepository priceRepository = mock(InstrumentPriceRepository.class);
        HoldingRepository holdingRepository = mock(HoldingRepository.class);
        when(holdingRepository.findDistinctActiveInstrumentIdsHeld()).thenReturn(List.of(inst.getId()));
        when(instrumentRepository.findAllById(any())).thenReturn(List.of(inst));
        LocalDate today = LocalDate.of(2026, 10, 20);
        InstrumentPrice existing = new InstrumentPrice(inst, today, new BigDecimal("1234"), PriceSource.YAHOO);
        when(priceRepository.findByInstrumentIdAndAsOfAndUserIdIsNull(inst.getId(), today)).thenReturn(Optional.of(existing));
        PriceRefreshService service = new PriceRefreshService(instrumentRepository, priceRepository, holdingRepository,
                List.of(provider), new PriceProperties(), mock(InstrumentClassificationService.class));

        assertEquals(0, service.refreshIntraday()); // hits the 429
        int sent = requests.size();
        handler = healthy();
        assertEquals(0, service.refreshIntraday()); // inside the cooldown
        assertEquals(sent, requests.size(), "no HTTP calls during the cooldown");
        verify(priceRepository, never()).save(any());
        assertEquals(0, new BigDecimal("1234").compareTo(existing.getClose()));
    }
}
