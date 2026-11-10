package com.financeos.statement.parser.layout;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses printed dates in a profile's java patterns; a pattern without a year takes it from the statement period. */
final class DateResolver {

    private record Fmt(DateTimeFormatter formatter, boolean yearless) {
    }

    private final List<Fmt> formats = new ArrayList<>();

    DateResolver(List<String> patterns) {
        for (String p : patterns) {
            boolean yearless = p.indexOf('y') < 0 && p.indexOf('u') < 0;
            DateTimeFormatterBuilder b = new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern(p.replace('y', 'u'));
            if (yearless) {
                b.parseDefaulting(ChronoField.YEAR, 2000); // a leap year, so 29 Feb parses
            }
            formats.add(new Fmt(b.toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT), yearless));
        }
    }

    /** The date, or null when no pattern fits (or a yearless date has no period to take its year from). */
    LocalDate parse(String text, LocalDate periodStart, LocalDate periodEnd) {
        if (periodStart == null && periodEnd != null) {
            periodStart = periodEnd.minusYears(1).plusDays(1); // only the end is printed: the year before it
        }
        String t = text.strip();
        for (Fmt f : formats) {
            LocalDate d;
            try {
                d = LocalDate.from(f.formatter().parse(t));
            } catch (DateTimeException e) {
                continue;
            }
            if (!f.yearless()) {
                return d;
            }
            LocalDate resolved = resolveYear(MonthDay.from(d), periodStart, periodEnd);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /** The year, in or next to the period, that puts the day nearest to (ideally inside) it; 29 Feb only in leap years. */
    static LocalDate resolveYear(MonthDay md, LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return null;
        }
        LocalDate best = null;
        long bestDist = Long.MAX_VALUE;
        for (int y = start.getYear() - 1; y <= end.getYear() + 1; y++) {
            if (!md.isValidYear(y)) {
                continue;
            }
            LocalDate c = md.atYear(y);
            long dist = c.isBefore(start) ? ChronoUnit.DAYS.between(c, start)
                    : c.isAfter(end) ? ChronoUnit.DAYS.between(end, c) : 0;
            if (dist < bestDist) {
                best = c;
                bestDist = dist;
            }
        }
        return best;
    }
}
