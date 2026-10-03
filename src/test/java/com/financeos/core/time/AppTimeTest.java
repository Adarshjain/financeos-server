package com.financeos.core.time;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class AppTimeTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 20:00 UTC on 10 March = 01:30 IST on 11 March. */
    private static final Instant LATE_EVENING_UTC = Instant.parse("2026-03-10T20:00:00Z");

    @AfterEach
    void reset() {
        AppTime.reset();
    }

    @Test
    void defaultZoneIsIndia() {
        AppTime.reset();
        assertEquals(IST, AppTime.zone());
        assertEquals("Asia/Kolkata", AppTime.DEFAULT_ZONE);
    }

    @Test
    void todayIsTheBusinessZoneDateNotTheUtcDate() {
        AppTime.useClock(Clock.fixed(LATE_EVENING_UTC, IST));
        assertEquals(LocalDate.of(2026, 3, 11), AppTime.today());
        assertEquals(IST, AppTime.zone());

        // the same instant on a UTC clock is still the 10th: what LocalDate.now() gave on the server
        AppTime.useClock(Clock.fixed(LATE_EVENING_UTC, ZoneOffset.UTC));
        assertEquals(LocalDate.of(2026, 3, 10), AppTime.today());
    }

    @Test
    void useZoneSwitchesTheBusinessZoneOnTheSystemClock() {
        AppTime.useZone(ZoneId.of("America/Los_Angeles"));
        assertEquals(ZoneId.of("America/Los_Angeles"), AppTime.zone());
        assertEquals(LocalDate.now(ZoneId.of("America/Los_Angeles")), AppTime.today());
    }

    @Test
    void resetRestoresTheDefaultZoneAfterAPinnedClock() {
        AppTime.useClock(Clock.fixed(LATE_EVENING_UTC, ZoneOffset.UTC));
        AppTime.reset();
        assertEquals(IST, AppTime.zone());
        assertEquals(LocalDate.now(IST), AppTime.today());
    }
}
