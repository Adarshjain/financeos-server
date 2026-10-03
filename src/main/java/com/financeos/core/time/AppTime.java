package com.financeos.core.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The business calendar. Every date in FinanceOS (transaction, statement, due and trade dates)
 * is a calendar date in the users' zone (IST), while the server runs on UTC; "today" must
 * therefore come from here, never {@code LocalDate.now()} (which is UTC on the server and a
 * day behind from 00:00 to 05:30 IST).
 *
 * <p>Static so entities and value objects can use it; the zone is set once at startup from
 * {@code app.zone} (see {@link AppTimeConfiguration}) and tests may pin a fixed clock.
 */
public final class AppTime {

    public static final String DEFAULT_ZONE = "Asia/Kolkata";

    private static volatile Clock clock = Clock.system(ZoneId.of(DEFAULT_ZONE));

    private AppTime() {
    }

    /** Today in the business zone. */
    public static LocalDate today() {
        return LocalDate.now(clock);
    }

    /** The business zone (for turning a business date into an instant, e.g. its midnight). */
    public static ZoneId zone() {
        return clock.getZone();
    }

    /** Sets the business zone (startup). */
    public static void useZone(ZoneId zone) {
        clock = Clock.system(zone);
    }

    /** Pins the clock (tests). */
    public static void useClock(Clock fixed) {
        clock = fixed;
    }

    /** Back to the system clock in the default zone (tests). */
    public static void reset() {
        clock = Clock.system(ZoneId.of(DEFAULT_ZONE));
    }
}
