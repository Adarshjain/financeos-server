package com.financeos.sms.line;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** The sender catalogue (resource sms/sender-catalogue.json): header to provider, bank and kind, plus phone-side patterns. */
@Service
public class SenderCatalogue {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Family(String key, String pattern) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sender(String header, String provider, String bank, String kind, boolean numberless, List<Family> families) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Drop(List<String> otpPatterns, List<String> promoHeaderSuffixes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndingPattern(String key, String pattern) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(List<Sender> senders, Drop drop, List<EndingPattern> endingPatterns) {}

    private static final Pattern OPERATOR_PREFIX = Pattern.compile("^[A-Z]{2}-");
    private static final Pattern SUFFIX = Pattern.compile("-[STPG]$");

    private final Data data;
    private final Map<String, Sender> byHeader = new HashMap<>();

    public SenderCatalogue(ObjectMapper mapper) {
        try (InputStream in = SenderCatalogue.class.getResourceAsStream("/sms/sender-catalogue.json")) {
            this.data = mapper.readValue(in, Data.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Sender s : data.senders()) {
            byHeader.put(s.header(), s);
        }
    }

    public Data data() {
        return data;
    }

    public Optional<Sender> find(String normalisedHeader) {
        return Optional.ofNullable(normalisedHeader == null ? null : byHeader.get(normalisedHeader));
    }

    /** "VM-HDFCBK-S" becomes "HDFCBK": the 2-letter operator prefix and the -S/-T/-P/-G suffix go, upper case. */
    public static String normaliseSender(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        s = OPERATOR_PREFIX.matcher(s).replaceFirst("");
        return SUFFIX.matcher(s).replaceFirst("");
    }
}
