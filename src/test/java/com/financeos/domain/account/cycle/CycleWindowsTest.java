package com.financeos.domain.account.cycle;

import com.financeos.domain.account.cycle.BillingCycles.Cycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CycleWindowsTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private final CycleWindows windows = new CycleWindows(Map.of(
            A, new Cycle(d(2026, 1, 5), d(2026, 2, 4), Source.PROJECTED),
            B, new Cycle(d(2026, 1, 20), d(2026, 2, 19), Source.STATEMENT)));

    @Test
    void containsDateInsideTheCardsOwnWindow() {
        assertTrue(windows.contains(A, d(2026, 1, 10)));
        assertTrue(windows.contains(B, d(2026, 2, 10)));
    }

    @Test
    void windowsArePerCard() {
        assertFalse(windows.contains(A, d(2026, 2, 10)));
        assertFalse(windows.contains(B, d(2026, 1, 10)));
    }

    @Test
    void unknownAccountNeverMatches() {
        assertFalse(windows.contains(UUID.randomUUID(), d(2026, 1, 10)));
    }

    @Test
    void nullsNeverMatch() {
        assertFalse(windows.contains(null, d(2026, 1, 10)));
        assertFalse(windows.contains(A, null));
    }

    @Test
    void earliestStartAndLatestEndSpanAllCards() {
        assertEquals(d(2026, 1, 5), windows.earliestStart());
        assertEquals(d(2026, 2, 19), windows.latestEnd());
    }

    @Test
    void emptyWindowsHaveNoBounds() {
        CycleWindows empty = new CycleWindows(Map.of());
        assertNull(empty.earliestStart());
        assertNull(empty.latestEnd());
        assertFalse(empty.contains(A, d(2026, 1, 10)));
    }
}
