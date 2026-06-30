package com.financeos.gmail.ingest.gemini;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

/**
 * Parses date strings in multiple common formats that Gemini may echo back,
 * including Indian-style dates from bank statement alerts.
 *
 * <p>Tried in order (first successful parse wins):
 * <ol>
 *   <li>ISO-8601: {@code yyyy-MM-dd} (e.g. 2026-07-05)</li>
 *   <li>Slash DMY: {@code d/M/yyyy} or {@code dd/MM/yyyy} (e.g. 05/07/2026, 5/7/2026)</li>
 *   <li>Dash DMY: {@code dd-MM-yyyy} (e.g. 05-07-2026)</li>
 *   <li>Named month: {@code d-MMM-yyyy} (e.g. 5-Jul-2026)</li>
 *   <li>Long month: {@code d MMMM yyyy} (e.g. 5 July 2026)</li>
 *   <li>Short month: {@code d MMM yyyy} (e.g. 5 Jul 2026)</li>
 *   <li>US MDY slash: {@code M/d/yyyy} — tried last to avoid ambiguity with DMY</li>
 * </ol>
 */
final class FlexibleDateParser {

    private FlexibleDateParser() {}

    /**
     * Ordered list of formatters, most-specific first.
     * All use SMART resolver style and English locale (month abbreviations).
     */
    private static final List<DateTimeFormatter> FORMATTERS = List.of(
            // ISO-8601: 2026-07-05
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            // Slash DMY: 05/07/2026 or 5/7/2026
            new DateTimeFormatterBuilder()
                    .appendPattern("d/M/yyyy")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.SMART),
            // Dash DMY numeric: 05-07-2026
            new DateTimeFormatterBuilder()
                    .appendPattern("dd-MM-yyyy")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.SMART),
            // Dash DMY with short month name: 5-Jul-2026
            new DateTimeFormatterBuilder()
                    .appendPattern("d-MMM-yyyy")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.SMART),
            // Space-separated long month: 5 July 2026
            new DateTimeFormatterBuilder()
                    .appendPattern("d MMMM yyyy")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.SMART),
            // Space-separated short month: 5 Jul 2026
            new DateTimeFormatterBuilder()
                    .appendPattern("d MMM yyyy")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.SMART),
            // US MDY slash (last — ambiguous with DMY): 07/05/2026
            // Placed last intentionally; Indian DD/MM/YYYY is far more common in this context.
            new DateTimeFormatterBuilder()
                    .appendPattern("M/d/yyyy")
                    .toFormatter(Locale.ENGLISH)
                    .withResolverStyle(ResolverStyle.SMART)
    );

    /**
     * Parse a date string by attempting each known format in order.
     *
     * @param text the raw date string from Gemini
     * @return parsed LocalDate
     * @throws DateTimeParseException if no format matches
     */
    static LocalDate parse(String text) {
        if (text == null || text.isBlank()) {
            throw new DateTimeParseException("Date string is null or blank", "", 0);
        }

        String trimmed = text.trim();

        for (DateTimeFormatter formatter : FORMATTERS) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }

        throw new DateTimeParseException(
                "Unable to parse date in any known format: " + trimmed, trimmed, 0);
    }
}
