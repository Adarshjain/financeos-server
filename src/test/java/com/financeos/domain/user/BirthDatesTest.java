package com.financeos.domain.user;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BirthDatesTest {

    @BeforeEach
    void fixClock() {
        AppTime.useClock(Clock.fixed(Instant.parse("2026-11-03T06:00:00Z"), ZoneId.of("Asia/Kolkata")));
    }

    @AfterEach
    void reset() {
        AppTime.reset();
    }

    @Test
    void acceptsFullAndPartialDates() {
        assertEquals("1990-04-05", BirthDates.normalize(" 1990-04-05 "));
        assertEquals("1900-01-01", BirthDates.normalize("1900-01-01"));
        assertEquals("2026-11-03", BirthDates.normalize("2026-11-03"));
        assertEquals("--02-29", BirthDates.normalize("--02-29"));
    }

    @Test
    void rejectsFutureEarlyAndMalformedDates() {
        assertThrows(ValidationException.class, () -> BirthDates.normalize("2026-11-04"));
        assertThrows(ValidationException.class, () -> BirthDates.normalize("1899-12-31"));
        assertThrows(ValidationException.class, () -> BirthDates.normalize("1990-02-30"));
        assertThrows(ValidationException.class, () -> BirthDates.normalize("--13-01"));
        assertThrows(ValidationException.class, () -> BirthDates.normalize("--02-30"));
        assertThrows(ValidationException.class, () -> BirthDates.normalize("05/04/1990"));
    }
}
