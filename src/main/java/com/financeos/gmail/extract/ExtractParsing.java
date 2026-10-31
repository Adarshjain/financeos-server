package com.financeos.gmail.extract;

import com.financeos.gmail.classify.EmailText;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parsing helpers shared by the template and generic tiers. */
public final class ExtractParsing {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\s.,;:\\-_/|]+$");
    private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^[\\s,;:\\-_/|]+");
    private static final Pattern ANY_SPACE = Pattern.compile("[\\s\\p{Zs}" + EmailText.SECTION + "]+");

    private ExtractParsing() {}

    /**
     * Amount from text such as "1,23,456.78", "Rs.1234", "INR 1,234" or "USD 40". Commas and spaces are
     * ignored; the first number wins. Null when there is no number or it is not positive.
     */
    public static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace(",", "").replaceAll("[\\s\\p{Zs}]+", "");
        Matcher m = NUMBER.matcher(cleaned);
        if (!m.find()) {
            return null;
        }
        BigDecimal value = new BigDecimal(m.group());
        return value.signum() > 0 ? value : null;
    }

    /** ₹ / Rs / Rs. / INR become INR; other 3-letter codes are upper-cased; null or blank gives {@code fallback}. */
    public static String parseCurrency(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String c = raw.trim().replaceAll("\\.$", "");
        String lower = c.toLowerCase(Locale.ROOT);
        if (c.equals("₹") || lower.equals("rs") || lower.equals("inr")) {
            return "INR";
        }
        switch (c) {
            case "$":
                return "USD";
            case "€":
                return "EUR";
            case "£":
                return "GBP";
            default:
                break;
        }
        if (c.matches("(?i)[a-z]{3}")) {
            return c.toUpperCase(Locale.ROOT);
        }
        return fallback;
    }

    /** Digits of the text, last four (fewer if fewer are shown); null when there are none. */
    public static String parseLast4(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        return digits.length() > 4 ? digits.substring(digits.length() - 4) : digits;
    }

    /** Trim, collapse spaces, strip trailing punctuation, then drop each strip pattern; empty gives null. */
    public static String cleanCounterparty(String raw, List<Pattern> strip) {
        if (raw == null) {
            return null;
        }
        String s = tidy(raw);
        if (strip != null) {
            for (Pattern p : strip) {
                s = tidy(p.matcher(s).replaceAll(""));
            }
        }
        return s.isEmpty() ? null : s;
    }

    private static String tidy(String s) {
        String collapsed = ANY_SPACE.matcher(s).replaceAll(" ").trim();
        collapsed = TRAILING_PUNCTUATION.matcher(collapsed).replaceFirst("");
        return LEADING_PUNCTUATION.matcher(collapsed).replaceFirst("").trim();
    }

    /**
     * A java.time pattern as a case-insensitive, English, strict formatter (31-02 does not parse). Year
     * letters {@code y} are read as proleptic years ({@code u}) so strict mode needs no era.
     */
    public static DateTimeFormatter dateFormatter(String pattern) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (char c : pattern.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            sb.append(!quoted && c == 'y' ? 'u' : c);
        }
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(sb.toString())
                .toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    /** True when the pattern has a year field; a pattern without one resolves its year from the sent date. */
    public static boolean hasYear(String pattern) {
        boolean quoted = false;
        for (char c : pattern.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && (c == 'y' || c == 'u')) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses the date with the first pattern that fits. A missing year takes the sent date's year, or the
     * year before when that would land after the sent date; a 2-digit year reads as 20yy. A date later than
     * {@code sentOn} plus one day is never returned (null instead); an unparseable date is null.
     */
    public static LocalDate parseDate(String raw, List<String> patterns, LocalDate sentOn) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = ANY_SPACE.matcher(raw).replaceAll(" ").trim();
        LocalDate limit = sentOn == null ? null : sentOn.plusDays(1);
        for (String pattern : patterns) {
            DateTimeFormatter f = dateFormatter(pattern);
            LocalDate date;
            try {
                if (hasYear(pattern)) {
                    date = LocalDate.parse(text, f);
                } else {
                    if (sentOn == null) {
                        continue;
                    }
                    MonthDay md = MonthDay.from(f.parse(text));
                    date = md.atYear(sentOn.getYear());
                    if (date.isAfter(limit)) {
                        date = md.atYear(sentOn.getYear() - 1);
                    }
                }
            } catch (DateTimeException e) {
                continue;
            }
            return limit != null && date.isAfter(limit) ? null : date;
        }
        return null;
    }

}
