package com.financeos.gmail.ingest.gemini;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static org.junit.jupiter.api.Assertions.*;

class FlexibleDateParserTest {

    @ParameterizedTest(name = "parse \"{0}\" → {1}")
    @CsvSource({
            // ISO-8601
            "2026-07-05,    2026-07-05",
            "2026-01-31,    2026-01-31",

            // Indian DD/MM/YYYY (slash)
            "05/07/2026,    2026-07-05",
            "5/7/2026,      2026-07-05",
            "31/01/2026,    2026-01-31",

            // DD-MM-YYYY (dash)
            "05-07-2026,    2026-07-05",
            "31-01-2026,    2026-01-31",

            // d-MMM-yyyy (abbreviated month)
            "5-Jul-2026,    2026-07-05",
            "31-Jan-2026,   2026-01-31",

            // d MMMM yyyy (full month name)
            "5 July 2026,   2026-07-05",
            "31 January 2026, 2026-01-31",

            // d MMM yyyy (short month, space)
            "5 Jul 2026,    2026-07-05",
            "31 Jan 2026,   2026-01-31",
    })
    void parsesKnownFormats(String input, LocalDate expected) {
        assertEquals(expected, FlexibleDateParser.parse(input));
    }

    @Test
    void parsesWithLeadingTrailingWhitespace() {
        assertEquals(LocalDate.of(2026, 7, 5), FlexibleDateParser.parse("  2026-07-05  "));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-date", "2026/13/45", "yesterday"})
    void rejectsUnparseableDates(String input) {
        assertThrows(DateTimeParseException.class, () -> FlexibleDateParser.parse(input));
    }
}
