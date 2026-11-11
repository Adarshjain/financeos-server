package com.financeos.sms.read;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Dates as the senders write them. A date without a year takes it from the received date. */
final class SmsDates {

    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
    private static final Pattern NUMERIC = Pattern.compile("(\\d{1,2})\\s*[-/.]\\s*(\\d{1,2})\\s*[-/.]\\s*(\\d{2,4})");
    private static final Pattern MONTH_FIRST = Pattern.compile("([A-Za-z]{3})[A-Za-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})");
    private static final Pattern DAY_FIRST = Pattern.compile("(\\d{1,2})\\s*[-/ ]?\\s*([A-Za-z]{3})[A-Za-z]*(?:[-/ ,.]*\\s*(\\d{2,4}))?");

    private SmsDates() {
    }

    /** Null when the text holds no date. */
    static LocalDate parse(String text, LocalDate received) {
        if (text == null) {
            return null;
        }
        Matcher n = NUMERIC.matcher(text);
        if (n.find()) {
            return build(Integer.parseInt(n.group(1)), Integer.parseInt(n.group(2)), n.group(3), received);
        }
        Matcher mf = MONTH_FIRST.matcher(text);
        while (mf.find()) {
            int month = month(mf.group(1));
            if (month > 0) {
                return build(Integer.parseInt(mf.group(2)), month, mf.group(3), received);
            }
        }
        Matcher df = DAY_FIRST.matcher(text);
        while (df.find()) {
            int month = month(df.group(2));
            if (month > 0) {
                return build(Integer.parseInt(df.group(1)), month, df.group(3), received);
            }
        }
        return null;
    }

    private static int month(String name) {
        return MONTHS.indexOf(name.toLowerCase(Locale.ROOT)) + 1;
    }

    private static LocalDate build(int day, int month, String year, LocalDate received) {
        try {
            if (year != null) {
                int y = Integer.parseInt(year);
                return LocalDate.of(y < 100 ? 2000 + y : y, month, day);
            }
            LocalDate d = LocalDate.of(received.getYear(), month, day);
            // A date more than a day after the message arrived is last year's.
            return d.isAfter(received.plusDays(1)) ? d.minusYears(1) : d;
        } catch (DateTimeException e) {
            return null;
        }
    }
}
