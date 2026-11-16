package com.financeos.domain.instrument.corporateaction.detect;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NseHostsTest {

    private final CorporateActionsProperties.Nse nse = new CorporateActionsProperties().getNse();

    @Test
    void theDefaultsAreTheNewExchangeHosts() {
        assertThat(nse.getBaseUrl()).isEqualTo("https://www.nseindia.in");
        assertThat(nse.getArchivesUrl()).isEqualTo("https://nsearchives.nseindia.in");
        assertThat(nse.getNameChangesUrl()).isEqualTo("https://nsearchives.nseindia.in/content/equities/namechange.csv");
    }

    @Test
    void bothArchiveHostsAreAcceptedOverHttpsAndNothingElse() {
        assertThat(nse.isArchiveLink("https://nsearchives.nseindia.in/corporate/a.pdf")).isTrue();
        assertThat(nse.isArchiveLink("https://nsearchives.nseindia.com/corporate/a.pdf")).isTrue();
        assertThat(nse.isArchiveLink("http://nsearchives.nseindia.in/corporate/a.pdf")).isFalse();
        assertThat(nse.isArchiveLink("http://nsearchives.nseindia.com/corporate/a.pdf")).isFalse();
        assertThat(nse.isArchiveLink("https://www.nseindia.in/corporate/a.pdf")).isFalse();
        assertThat(nse.isArchiveLink("https://other.example/a.pdf")).isFalse();
        assertThat(nse.isArchiveLink("https://nsearchives.nseindia.in.evil.example/a.pdf")).isFalse();
        assertThat(nse.isArchiveLink("https://nsearchives.nseindia.in:8443/a.pdf")).isFalse();
    }

    @Test
    void aConfiguredLocalHostIsAlwaysAllowed() {
        nse.setArchivesUrl("http://localhost:8089/archives");
        nse.setBaseUrl("http://localhost:8089/nse");

        assertThat(nse.isArchiveLink("http://localhost:8089/archives/x.pdf")).isTrue();
        assertThat(nse.isArchiveLink("http://localhost:9999/x.pdf")).isFalse();
        assertThat(nse.allowedHosts()).contains("localhost", "www.nseindia.com", "www.nseindia.in",
                "nsearchives.nseindia.com", "nsearchives.nseindia.in");
        assertThat(nse.archiveLink("https://nsearchives.nseindia.in/c/a.pdf")).isEqualTo("http://localhost:8089/archives/c/a.pdf");
    }

    @Test
    void aRedirectBetweenTheExchangeHostsIsFollowed() {
        URI from = URI.create("https://www.nseindia.com/api/x?a=1");

        assertThat(NseHttp.redirectTarget(nse, from, "https://www.nseindia.in/api/x?a=1", 0))
                .isEqualTo(URI.create("https://www.nseindia.in/api/x?a=1"));
        assertThat(NseHttp.redirectTarget(nse, URI.create("https://www.nseindia.in/a"), "https://www.nseindia.com/a", 2)).isNotNull();
        assertThat(NseHttp.redirectTarget(nse, from, "/other", 0)).isEqualTo(URI.create("https://www.nseindia.com/other"));
    }

    @Test
    void aRedirectToAnUnknownHostAnHttpUrlOrPastThreeHopsIsRefused() {
        URI from = URI.create("https://www.nseindia.in/api/x");

        assertThat(NseHttp.redirectTarget(nse, from, "https://evil.example/x", 0)).isNull();
        assertThat(NseHttp.redirectTarget(nse, from, "https://www.nseindia.in.evil.example/x", 0)).isNull();
        assertThat(NseHttp.redirectTarget(nse, from, "http://nsearchives.nseindia.in/x", 0)).isNull();
        assertThat(NseHttp.redirectTarget(nse, from, null, 0)).isNull();
        assertThat(NseHttp.redirectTarget(nse, from, "https://www.nseindia.com/x", NseHttp.MAX_HOPS)).isNull();
    }

    @Test
    void aSameHostRedirectNeverDowngradesFromHttps() {
        URI from = URI.create("https://www.nseindia.in/api/x");

        assertThat(NseHttp.redirectTarget(nse, from, "http://www.nseindia.in/api/x", 0)).isNull();
        assertThat(NseHttp.redirectTarget(nse, from, "https://www.nseindia.in:8443/api/x", 0)).isNotNull();
        assertThat(NseHttp.redirectTarget(nse, URI.create("http://localhost:8089/a"), "http://localhost:8089/b", 0))
                .isEqualTo(URI.create("http://localhost:8089/b"));
    }

    @Test
    void onlyRequestsOnTheRunsOwnThreadCountAndAnEmptyRunLogsNothing() throws Exception {
        NseRequestMonitor monitor = new NseRequestMonitor(new SimpleMeterRegistry());

        monitor.startRun();
        Thread other = new Thread(() -> monitor.error("listings", "www.nseindia.in"));
        other.start();
        other.join();
        assertThat(monitor.finishRun()).as("another thread's failure is not the run's").isFalse();

        monitor.startRun();
        monitor.error("listings", "www.nseindia.in");
        assertThat(monitor.finishRun()).as("its own failure counts").isTrue();
        assertThat(monitor.finishRun()).as("the token is cleared").isFalse();
    }

    @Test
    void aRedirectLoopStopsAfterThreeHops() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().add("Location", "/again");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        s.start();
        try {
            NseAnnouncementsClient.Response r = new NseAnnouncementsClient.HttpTransport()
                    .get("http://127.0.0.1:" + s.getAddress().getPort() + "/a", Map.of(), 1024);

            assertThat(r.status()).isEqualTo(302);
            assertThat(hits.get()).isEqualTo(NseHttp.MAX_HOPS + 1);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void theMonitorCountsByClientAndResultAndFlagsAFullyFailedRun() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NseRequestMonitor monitor = new NseRequestMonitor(registry);

        monitor.startRun();
        assertThat(monitor.finishRun()).as("no attempts, nothing to report").isFalse();

        monitor.startRun();
        monitor.response("announcements", 301, "www.nseindia.com");
        monitor.error("listings", "nsearchives.nseindia.in");
        assertThat(monitor.finishRun()).isTrue();

        monitor.startRun();
        monitor.response("announcements", 301, "www.nseindia.com");
        monitor.response("announcements", 200, "www.nseindia.in");
        assertThat(monitor.finishRun()).as("one success is enough").isFalse();

        assertThat(registry.get("financeos.nse.requests").tag("client", "announcements").tag("result", "http_301").counter().count()).isEqualTo(2);
        assertThat(registry.get("financeos.nse.requests").tag("client", "announcements").tag("result", "ok").counter().count()).isEqualTo(1);
        assertThat(registry.get("financeos.nse.requests").tag("client", "listings").tag("result", "error").counter().count()).isEqualTo(1);
    }
}
