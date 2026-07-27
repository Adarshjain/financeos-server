package com.financeos.domain.instrument.price;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
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
public class AmfiPriceProvider implements PriceProvider {

    private static final Logger log = LoggerFactory.getLogger(AmfiPriceProvider.class);

    private static final DateTimeFormatter AMFI_DATE_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    private final PriceProperties priceProperties;
    private final HttpClient httpClient;

    public AmfiPriceProvider(PriceProperties priceProperties) {
        this.priceProperties = priceProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public PriceSource source() {
        return PriceSource.AMFI;
    }

    @Override
    public boolean supports(Instrument instrument) {
        return instrument != null && instrument.getType() == InstrumentType.mutual_fund;
    }

    @Override
    public Map<UUID, PriceQuote> fetch(List<Instrument> instruments) {
        Map<UUID, PriceQuote> result = new HashMap<>();
        List<Instrument> targetInstruments = instruments.stream()
                .filter(this::supports)
                .toList();

        if (targetInstruments.isEmpty()) {
            return result;
        }

        PriceProperties.ProviderProperties props = priceProperties.getProviders().get("amfi");
        if (props != null && !props.isEnabled()) {
            log.info("AMFI price provider is disabled in configuration.");
            return result;
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
                return result;
            }

            Map<String, PriceQuote> navBySchemeCode = new HashMap<>();
            Map<String, PriceQuote> navByIsin = new HashMap<>();

            parseAmfiFeed(response.body(), navBySchemeCode, navByIsin);

            for (Instrument inst : targetInstruments) {
                PriceQuote quote = null;
                if (inst.getAmfiCode() != null && !inst.getAmfiCode().isBlank()) {
                    quote = navBySchemeCode.get(inst.getAmfiCode().trim());
                }
                if (quote == null && inst.getIsin() != null && !inst.getIsin().isBlank()) {
                    quote = navByIsin.get(inst.getIsin().trim());
                }

                if (quote != null) {
                    result.put(inst.getId(), quote);
                } else {
                    log.debug("No AMFI NAV quote matched for instrument: {} (amfiCode={}, isin={})",
                            inst.getName(), inst.getAmfiCode(), inst.getIsin());
                }
            }

        } catch (Exception e) {
            log.error("Error fetching AMFI NAV prices", e);
        }

        return result;
    }

    private void parseAmfiFeed(String content, Map<String, PriceQuote> navBySchemeCode, Map<String, PriceQuote> navByIsin) {
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
            String navStr = parts[4].trim();
            String dateStr = parts[5].trim();

            if (navStr.equalsIgnoreCase("N.A.") || navStr.isBlank()) {
                continue;
            }

            try {
                BigDecimal nav = new BigDecimal(navStr);
                LocalDate asOf = LocalDate.parse(dateStr, AMFI_DATE_FORMATTER);
                PriceQuote quote = new PriceQuote(nav, asOf);

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
