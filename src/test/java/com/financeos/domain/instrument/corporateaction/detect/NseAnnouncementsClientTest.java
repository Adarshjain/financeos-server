package com.financeos.domain.instrument.corporateaction.detect;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.instrument.corporateaction.detect.NseAnnouncementsClient.Document;
import com.financeos.domain.instrument.corporateaction.detect.NseAnnouncementsClient.Listed;
import com.financeos.domain.instrument.corporateaction.detect.NseAnnouncementsClient.Response;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The notices client with a fake transport: no network, no sleeping. */
class NseAnnouncementsClientTest {

    private final List<String> urls = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private Transport transport = (url, headers, max) -> new Response(200, "[]".getBytes(StandardCharsets.UTF_8), false);

    private interface Transport extends NseAnnouncementsClient.Transport {
    }

    private NseAnnouncementsClient client() {
        NseAnnouncementsClient.Transport recording = (url, headers, max) -> {
            urls.add(url);
            return transport.get(url, headers, max);
        };
        return new NseAnnouncementsClient(new CorporateActionsProperties(), new ObjectMapper(), recording, clock::get, ms -> {
            sleeps.add(ms);
            clock.addAndGet(ms);
        });
    }

    private static Response json(String body) {
        return new Response(200, body.getBytes(StandardCharsets.UTF_8), false);
    }

    private static byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.newLineAtOffset(20, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] blankPdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void asksInWindowsOfAtMost365DaysForEverySymbolAndWarmsUpOnce() {
        Set<String> symbols = new LinkedHashSet<>(List.of("ACMEOLD", "ACMENEW"));
        Listed out = client().list(symbols, LocalDate.of(2022, 1, 1), LocalDate.of(2024, 3, 1));

        assertThat(out.failed()).isFalse();
        List<String> api = urls.stream().filter(u -> u.contains("/api/corporate-announcements")).toList();
        assertThat(api).hasSize(6); // 2 symbols x 3 windows
        assertThat(urls.stream().filter(u -> u.contains("corporate-filings-announcements")).count()).isEqualTo(1);
        assertThat(api.get(0)).contains("symbol=ACMEOLD", "from_date=01-01-2022", "to_date=31-12-2022");
        assertThat(api.get(1)).contains("from_date=01-01-2023", "to_date=31-12-2023");
        assertThat(api.get(2)).contains("from_date=01-01-2024", "to_date=01-03-2024");
        assertThat(api.get(3)).contains("symbol=ACMENEW");
    }

    @Test
    void waitsAboutOneSecondBetweenRequests() {
        client().list(Set.of("ACME"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 1));

        // warm-up and the one window: the second request had to wait out the gap
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isEqualTo(1000L);
    }

    @Test
    void parsesNoticesAndDropsRepeatedFiles() {
        transport = (url, headers, max) -> url.contains("/api/") ? json("""
                [{"symbol":"ACME","desc":"Record Date","attchmntText":"Record date for the demerger is 07-Jun-2022",
                  "attchmntFile":"https://nsearchives.example/corporate/ACME_1.pdf","an_dt":"07-Jun-2022 15:12:41","sm_isin":"INE000A01011"},
                 {"symbol":"ACME","desc":"Record Date","attchmntText":"again",
                  "attchmntFile":"https://nsearchives.example/corporate/ACME_1.pdf","an_dt":"08-Jun-2022 10:00:00"}]
                """) : json("");

        Listed out = client().list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 1));

        assertThat(out.notices()).hasSize(1);
        assertThat(out.notices().get(0).date()).isEqualTo(LocalDate.of(2022, 6, 7));
        assertThat(out.notices().get(0).summary()).contains("demerger");
        assertThat(out.notices().get(0).isin()).isEqualTo("INE000A01011");
    }

    @Test
    void acceptsAnObjectWithData() {
        transport = (url, headers, max) -> json("{\"data\":[{\"symbol\":\"ACME\",\"desc\":\"x\",\"attchmntText\":\"y\",\"an_dt\":\"01-Jan-2022 00:00:00\"}]}");

        assertThat(client().list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 2, 1)).notices()).hasSize(1);
    }

    @Test
    void aFailedRequestIsFlaggedAndNeverThrown() {
        transport = (url, headers, max) -> url.contains("/api/") ? new Response(403, new byte[0], false) : json("");
        assertThat(client().list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 2, 1)).failed()).isTrue();

        transport = (url, headers, max) -> {
            throw new java.io.IOException("connection reset");
        };
        Listed out = client().list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 2, 1));
        assertThat(out.failed()).isTrue();
        assertThat(out.notices()).isEmpty();

        transport = (url, headers, max) -> json("<html>blocked</html>");
        assertThat(client().list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 2, 1)).failed()).isTrue();
    }

    @Test
    void aFailedWindowKeepsTheNoticesOfTheOthers() {
        transport = (url, headers, max) -> {
            if (!url.contains("/api/")) {
                return json("");
            }
            return url.contains("from_date=01-01-2023") ? new Response(500, new byte[0], false)
                    : json("[{\"symbol\":\"ACME\",\"desc\":\"d\",\"attchmntText\":\"t\",\"attchmntFile\":\"https://x.example/a.pdf\",\"an_dt\":\"01-02-2022\"}]");
        };

        Listed out = client().list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2023, 6, 1));

        assertThat(out.failed()).isTrue();
        assertThat(out.notices()).hasSize(1);
    }

    @Test
    void readsTheTextOfADocument() throws Exception {
        String sentence = "Record Date for the scheme is 07 June 2022 and the shareholders will receive one share for every ten shares held. "
                .repeat(1);
        byte[] pdf = pdfWithText(sentence + sentence + sentence + sentence);
        transport = (url, headers, max) -> new Response(200, pdf, false);

        Document d = client().fetchDocument("https://nsearchives.nseindia.com/corporate/ACME_1.pdf");

        assertThat(d.ok()).isTrue();
        assertThat(d.scanned()).isFalse();
        assertThat(d.text()).contains("for every ten shares");
    }

    @Test
    void aDocumentWithNoTextLayerIsAScan() throws Exception {
        byte[] pdf = blankPdf();
        transport = (url, headers, max) -> new Response(200, pdf, false);

        Document d = client().fetchDocument("https://nsearchives.nseindia.com/corporate/ACME_scan.pdf");

        assertThat(d.ok()).isTrue();
        assertThat(d.scanned()).isTrue();
        assertThat(d.text()).isEmpty();
    }

    @Test
    void anOversizedOrBrokenOrMissingDocumentFailsQuietly() {
        transport = (url, headers, max) -> new Response(200, new byte[0], true);
        assertThat(client().fetchDocument("https://x.example/big.pdf").ok()).isFalse();

        transport = (url, headers, max) -> new Response(200, "not a pdf".getBytes(StandardCharsets.UTF_8), false);
        assertThat(client().fetchDocument("https://x.example/bad.pdf").ok()).isFalse();

        transport = (url, headers, max) -> new Response(404, new byte[0], false);
        assertThat(client().fetchDocument("https://x.example/gone.pdf").ok()).isFalse();

        transport = (url, headers, max) -> {
            throw new java.net.SocketTimeoutException("timed out");
        };
        assertThat(client().fetchDocument("https://x.example/slow.pdf").ok()).isFalse();
    }

    @Test
    void documentSizeCapIsFifteenMegabytesAndItIsPassedToTheTransport() {
        List<Integer> caps = new ArrayList<>();
        transport = (url, headers, max) -> {
            caps.add(max);
            return new Response(200, new byte[0], true);
        };

        client().fetchDocument("https://nsearchives.nseindia.com/a.pdf");

        assertThat(caps).containsExactly(15 * 1024 * 1024);
    }

    @Test
    void refusesToRunInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            Listed listed = client().list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 2, 1));
            Document doc = client().fetchDocument("https://nsearchives.nseindia.com/a.pdf");

            assertThat(listed.failed()).isTrue();
            assertThat(doc.ok()).isFalse();
            assertThat(urls).isEmpty();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void noSymbolsOrAnEmptyRangeIsNoRequest() {
        assertThat(client().list(Set.of(), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 2, 1)).failed()).isFalse();
        assertThat(client().list(Set.of("ACME"), LocalDate.of(2022, 2, 1), LocalDate.of(2022, 1, 1)).notices()).isEmpty();
        assertThat(urls).isEmpty();
    }

    @Test
    void aNoticeLinkOnTheRealArchiveHostIsFetchedFromTheConfiguredArchiveHost() {
        CorporateActionsProperties props = new CorporateActionsProperties();
        props.getNse().setArchivesUrl("http://localhost:8089/archives/");
        props.getNse().setBaseUrl("http://localhost:8089/nse");
        List<String> got = new ArrayList<>();
        NseAnnouncementsClient c = new NseAnnouncementsClient(props, new ObjectMapper(), (url, h, max) -> {
            got.add(url);
            return new Response(404, new byte[0], false);
        }, clock::get, ms -> { });

        c.fetchDocument("https://nsearchives.nseindia.com/corporate/ACME_1.pdf");
        c.fetchDocument("https://other.example/x.pdf");
        c.list(Set.of("ACME"), LocalDate.of(2022, 1, 1), LocalDate.of(2022, 2, 1));

        assertThat(got.get(0)).isEqualTo("http://localhost:8089/archives/corporate/ACME_1.pdf");
        assertThat(got).doesNotContain("https://other.example/x.pdf"); // a foreign host is never fetched
        assertThat(got).anyMatch(u -> u.startsWith("http://localhost:8089/nse/api/corporate-announcements?index=equities&symbol=ACME&from_date=01-01-2022&to_date=01-02-2022"));
        assertThat(got).anyMatch(u -> u.equals("http://localhost:8089/nse/companies-listing/corporate-filings-announcements"));
        assertThat(props.getNse().getListingsUrl()).isEqualTo("http://localhost:8089/archives/content/equities/EQUITY_L.csv");
        assertThat(props.getNse().getNameChangesUrl()).isEqualTo("http://localhost:8089/archives/content/equities/namechange.csv");
        props.getNse().setListingsUrl("http://x.example/list.csv");
        assertThat(props.getNse().getListingsUrl()).isEqualTo("http://x.example/list.csv");
    }

    @Test
    void theDefaultsAreTheRealExchangeHosts() {
        CorporateActionsProperties props = new CorporateActionsProperties();

        assertThat(props.getNse().getListingsUrl()).isEqualTo("https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv");
        assertThat(props.getNse().archiveLink("https://nsearchives.nseindia.com/corporate/a.pdf")).isEqualTo("https://nsearchives.nseindia.com/corporate/a.pdf");
    }

    @Test
    void aDocumentOnAnotherHostOrWithAnotherSchemeIsRefusedWithoutARequest() {
        for (String link : new String[] {"https://other.example/x.pdf", "https://nsearchives.nseindia.com.evil.example/x.pdf",
                "ftp://nsearchives.nseindia.com/x.pdf", "/corporate/x.pdf", "not a url"}) {
            assertThat(client().fetchDocument(link).ok()).as(link).isFalse();
        }
        assertThat(urls).isEmpty();
    }

    private static com.sun.net.httpserver.HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", handler);
        s.start();
        return s;
    }

    @Test
    void theRealTransportNeverFollowsARedirectToAnotherHost() throws Exception {
        AtomicLong otherHits = new AtomicLong();
        com.sun.net.httpserver.HttpServer other = server(ex -> {
            otherHits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        com.sun.net.httpserver.HttpServer first = server(ex -> {
            // "localhost" and "127.0.0.1" are different hosts
            ex.getResponseHeaders().add("Location", "http://localhost:" + other.getAddress().getPort() + "/x.pdf");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        try {
            Response r = new NseAnnouncementsClient.HttpTransport().get("http://127.0.0.1:" + first.getAddress().getPort() + "/a.pdf", Map.of(), 1024);

            assertThat(r.status()).isEqualTo(302);
            assertThat(otherHits.get()).isZero();
        } finally {
            first.stop(0);
            other.stop(0);
        }
    }

    @Test
    void theRealTransportFollowsARedirectOnTheSameHost() throws Exception {
        com.sun.net.httpserver.HttpServer s = server(ex -> {
            if (ex.getRequestURI().getPath().equals("/a.pdf")) {
                ex.getResponseHeaders().add("Location", "/b.pdf");
                ex.sendResponseHeaders(302, -1);
            } else {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        try {
            Response r = new NseAnnouncementsClient.HttpTransport().get("http://127.0.0.1:" + s.getAddress().getPort() + "/a.pdf", Map.of(), 1024);

            assertThat(r.status()).isEqualTo(200);
            assertThat(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo("ok");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aBodyThatStallsIsGivenUpOnAtTheTotalDeadline() throws Exception {
        com.sun.net.httpserver.HttpServer s = server(ex -> {
            try {
                ex.sendResponseHeaders(200, 100_000);
                ex.getResponseBody().write(new byte[10]);
                ex.getResponseBody().flush();
                Thread.sleep(3000);
            } catch (Exception ignored) {
                // the client went away
            } finally {
                ex.close();
            }
        });
        try {
            long start = System.nanoTime();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new NseAnnouncementsClient.HttpTransport(1000).get("http://127.0.0.1:" + s.getAddress().getPort() + "/slow.pdf", Map.of(), 1024 * 1024))
                    .isInstanceOf(java.io.IOException.class);
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
        } finally {
            s.stop(0);
        }
    }
}
