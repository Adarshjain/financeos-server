package com.financeos.statement.parser;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.SignStyle;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Dates {
    private Dates() {
    }

    public static final List<String> DATE_FORMATS = List.of(
            "%d/%m/%Y", "%d/%m/%y", "%m/%d/%Y", "%m/%d/%y",
            "%d-%m-%Y", "%d-%m-%y", "%d.%m.%Y", "%d.%m.%y",
            "%d-%b-%Y", "%d-%b-%y", "%d/%b/%Y", "%d/%b/%y",
            "%d %b %Y", "%d %b %y", "%d %B %Y", "%Y-%m-%d",
            "%d-%B-%Y", "%b %d %Y", "%B %d %Y", "%b %d %y",
            "%d%b%Y", "%d%b%y", "%d%B%Y", "%Y/%m/%d",
            "%d%b", "%d%B", "%d %b", "%d-%b", "%d/%b");

    private static final Map<String, DateTimeFormatter> FORMATTERS;

    static {
        Map<String, DateTimeFormatter> m = new LinkedHashMap<>();
        for (String fmtId : DATE_FORMATS) {
            m.put(fmtId, buildFormatter(fmtId));
        }
        FORMATTERS = Collections.unmodifiableMap(m);
    }

    private static DateTimeFormatter buildFormatter(String fmtId) {
        DateTimeFormatterBuilder b = new DateTimeFormatterBuilder().parseCaseInsensitive();
        int i = 0;
        while (i < fmtId.length()) {
            char c = fmtId.charAt(i);
            if (c == '%' && i + 1 < fmtId.length()) {
                char spec = fmtId.charAt(i + 1);
                switch (spec) {
                    case 'd' -> b.appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE);
                    case 'm' -> b.appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE);
                    case 'Y' -> b.appendValue(ChronoField.YEAR, 4);
                    case 'y' -> b.appendValueReduced(ChronoField.YEAR, 2, 2, 2000);
                    case 'b' -> b.appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT);
                    case 'B' -> b.appendText(ChronoField.MONTH_OF_YEAR, TextStyle.FULL);
                    default -> throw new IllegalStateException("unsupported format directive %" + spec);
                }
                i += 2;
            } else {
                b.appendLiteral(c);
                i++;
            }
        }
        // Yearless formats resolve to year 1900, same as Python strptime; downstream
        // logic detects year==1900 and injects the statement-period year.
        if (!fmtId.contains("%Y") && !fmtId.contains("%y")) {
            b.parseDefaulting(ChronoField.YEAR, 1900);
        }
        return b.toFormatter(Locale.ENGLISH);
    }

    static LocalDate parse(String text, String fmtId) {
        DateTimeFormatter fmt = FORMATTERS.get(fmtId);
        if (fmt == null) {
            return null;
        }
        try {
            return LocalDate.from(fmt.parse(text));
        } catch (DateTimeException e) {
            return null;
        }
    }

    static DateAnchor dateAnchor(List<Word> words) {
        int maxStart = 3, maxSpan = 3;
        int n = words.size();
        for (int start = 0; start < Math.min(maxStart, n); start++) {
            for (int span = Math.min(maxSpan, n - start); span >= 1; span--) {
                String cand = joinNorm(words, start, span);
                if (Tokens.isDateToken(cand)) {
                    return new DateAnchor(start, span, cand);
                }
            }
        }
        return null;
    }

    static List<LocalDate> findDatesInWords(List<Word> words, List<String> fmtIds) {
        List<LocalDate> found = new ArrayList<>();
        int n = words.size();
        int i = 0;
        while (i < n) {
            int consumed = 0;
            for (int span : new int[]{3, 2, 1}) {
                if (i + span > n) {
                    continue;
                }
                String cand = joinNorm(words, i, span);
                if (!Tokens.isDateToken(cand)) {
                    continue;
                }
                for (String fmtId : fmtIds) {
                    LocalDate d = parse(cand, fmtId);
                    if (d != null) {
                        found.add(d);
                        consumed = span;
                        break;
                    }
                }
                if (consumed != 0) {
                    break;
                }
            }
            i += consumed != 0 ? consumed : 1;
        }
        return found;
    }

    static String inferDateFormat(List<String> tokens) {
        String best = null;
        int bestParsed = 0;
        int bestOrdered = -1;
        for (String fmtId : DATE_FORMATS) {
            List<LocalDate> parsed = new ArrayList<>();
            for (String t : tokens) {
                LocalDate d = parse(t, fmtId);
                if (d != null) {
                    parsed.add(d);
                }
            }
            int ordered = 0;
            for (int i = 0; i + 1 < parsed.size(); i++) {
                if (!parsed.get(i + 1).isBefore(parsed.get(i))) {
                    ordered++;
                }
            }
            int parsedCount = parsed.size();
            if (parsedCount > bestParsed || (parsedCount == bestParsed && ordered > bestOrdered)) {
                best = fmtId;
                bestParsed = parsedCount;
                bestOrdered = ordered;
            }
        }
        return best;
    }

    private static String joinNorm(List<Word> words, int start, int span) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < start + span; i++) {
            if (i > start) {
                sb.append(' ');
            }
            sb.append(Tokens.norm(words.get(i).text()));
        }
        return sb.toString();
    }
}
