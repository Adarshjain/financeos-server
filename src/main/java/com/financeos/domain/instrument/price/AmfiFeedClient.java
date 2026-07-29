package com.financeos.domain.instrument.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

@Component
public class AmfiFeedClient {

    private static final Logger log = LoggerFactory.getLogger(AmfiFeedClient.class);

    private static final DateTimeFormatter AMFI_DATE_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    private final PriceProperties priceProperties;
    private final HttpClient httpClient;

    private volatile boolean loaded = false;
    private volatile List<AmfiScheme> cachedSchemes = List.of();
    private volatile Map<String, PriceQuote> navBySchemeCode = Map.of();
    private volatile Map<String, PriceQuote> navByIsin = Map.of();

    public AmfiFeedClient(PriceProperties priceProperties) {
        this.priceProperties = priceProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // AMFI now 302-redirects www.amfiindia.com -> portal.amfiindia.com for NAVAll.txt.
                // The JDK HttpClient default is Redirect.NEVER, so without this the feed download
                // gets the 302 (non-200) and silently returns empty — breaking MF search AND MF pricing.
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

    public synchronized void load() {
        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("amfi");
        if (props != null && !props.isEnabled()) {
            log.info("AMFI price provider is disabled in configuration.");
            return;
        }

        String baseUrl = props != null && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                ? props.getBaseUrl()
                : "https://www.amfiindia.com";
        String url = baseUrl.replaceAll("/+$", "") + "/spages/NAVAll.txt";
        long timeoutMs = props != null && props.getTimeoutMs() > 0 ? props.getTimeoutMs() : 30000L;

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Failed to fetch AMFI NAV file. HTTP status: {}", response.statusCode());
                return;
            }

            List<AmfiScheme> schemes = new ArrayList<>();
            Map<String, PriceQuote> bySchemeCode = new HashMap<>();
            Map<String, PriceQuote> byIsin = new HashMap<>();

            parseAmfiFeed(response.body(), schemes, bySchemeCode, byIsin);

            this.cachedSchemes = List.copyOf(schemes);
            this.navBySchemeCode = Map.copyOf(bySchemeCode);
            this.navByIsin = Map.copyOf(byIsin);
            this.loaded = true;
            log.info("Loaded AMFI feed with {} schemes.", this.cachedSchemes.size());
        } catch (Exception e) {
            log.error("Error fetching AMFI NAV prices", e);
        }
    }

    public List<AmfiScheme> all() {
        ensureLoaded();
        return cachedSchemes;
    }

    public PriceQuote getQuoteBySchemeCode(String schemeCode) {
        ensureLoaded();
        if (schemeCode == null || schemeCode.isBlank()) {
            return null;
        }
        return navBySchemeCode.get(schemeCode.trim());
    }

    public PriceQuote getQuoteByIsin(String isin) {
        ensureLoaded();
        if (isin == null || isin.isBlank()) {
            return null;
        }
        return navByIsin.get(isin.trim());
    }

    private void parseAmfiFeed(String content, List<AmfiScheme> schemes, Map<String, PriceQuote> navBySchemeCode, Map<String, PriceQuote> navByIsin) {
        if (content == null || content.isBlank()) {
            return;
        }

        String[] lines = content.split("\r?\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("Scheme Code") || !line.contains(";")) {
                continue;
            }

            String[] parts = line.split(";");
            if (parts.length < 6) {
                continue;
            }

            String schemeCode = parts[0].trim();
            String isinPayout = parts[1].trim();
            String isinReinvest = parts[2].trim();
            String name = parts[3].trim();
            String navStr = parts[4].trim();
            String dateStr = parts[5].trim();

            if (navStr.equalsIgnoreCase("N.A.") || navStr.isBlank()) {
                continue;
            }

            try {
                BigDecimal nav = new BigDecimal(navStr);
                LocalDate asOf = LocalDate.parse(dateStr, AMFI_DATE_FORMATTER);
                PriceQuote quote = new PriceQuote(nav, asOf);

                String primaryIsin = !isinPayout.isBlank() ? isinPayout : (!isinReinvest.isBlank() ? isinReinvest : null);
                schemes.add(new AmfiScheme(schemeCode, primaryIsin, name, nav, asOf));

                if (!schemeCode.isBlank()) {
                    navBySchemeCode.putIfAbsent(schemeCode, quote);
                }
                if (!isinPayout.isBlank()) {
                    navByIsin.putIfAbsent(isinPayout, quote);
                }
                if (!isinReinvest.isBlank()) {
                    navByIsin.putIfAbsent(isinReinvest, quote);
                }
            } catch (Exception e) {
                // Ignore line parsing error and continue
            }
        }
    }
}
