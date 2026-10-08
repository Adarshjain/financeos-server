package com.financeos.domain.inbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InboxItemStateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);
    private final UUID userId = UUID.randomUUID();

    @Test
    void constructorSetsTheOwnerAndKeyWithNoState() {
        InboxItemState state = new InboxItemState(userId, "bill:x");
        assertEquals(userId, state.getUserId());
        assertEquals("bill:x", state.getItemKey());
        assertNull(state.getSnoozedUntil());
        assertNull(state.getDismissedAt());
    }

    @Test
    void aStateWithNothingSetHidesNothing() {
        assertFalse(new InboxItemState(userId, "k").hides(TODAY));
    }

    @Test
    void aDismissedRowStaysHidden() {
        InboxItemState state = new InboxItemState(userId, "k");
        state.setDismissedAt(Instant.parse("2026-01-01T00:00:00Z"));
        assertTrue(state.hides(TODAY));
        assertTrue(state.hides(TODAY.plusYears(5)));
    }

    @Test
    void aSnoozedRowIsHiddenBeforeTheDate() {
        InboxItemState state = new InboxItemState(userId, "k");
        state.setSnoozedUntil(TODAY.plusDays(1));
        assertTrue(state.hides(TODAY));
    }

    @Test
    void aSnoozedRowReturnsOnTheDate() {
        InboxItemState state = new InboxItemState(userId, "k");
        state.setSnoozedUntil(TODAY);
        assertFalse(state.hides(TODAY));
    }

    @Test
    void aSnoozedRowStaysBackAfterTheDate() {
        InboxItemState state = new InboxItemState(userId, "k");
        state.setSnoozedUntil(TODAY.minusDays(3));
        assertFalse(state.hides(TODAY));
    }

    @Test
    void dismissalWinsOverAnExpiredSnooze() {
        InboxItemState state = new InboxItemState(userId, "k");
        state.setSnoozedUntil(TODAY.minusDays(3));
        state.setDismissedAt(Instant.parse("2026-01-01T00:00:00Z"));
        assertTrue(state.hides(TODAY));
    }

    @Test
    void persistStampsBothTimesAndUpdateOnlyTheUpdatedTime() {
        InboxItemState state = new InboxItemState(userId, "k");
        state.onCreate();
        Instant created = state.getCreatedAt();
        assertNotNull(created);
        assertNotNull(state.getUpdatedAt());

        Instant earlier = Instant.parse("2020-01-01T00:00:00Z");
        state.setUpdatedAt(earlier);
        state.onUpdate();
        assertEquals(created, state.getCreatedAt());
        assertTrue(state.getUpdatedAt().isAfter(earlier));
    }

    @Test
    void persistKeepsAnExistingCreatedAt() {
        InboxItemState state = new InboxItemState(userId, "k");
        Instant created = Instant.parse("2020-01-01T00:00:00Z");
        state.setCreatedAt(created);
        state.onCreate();
        assertEquals(created, state.getCreatedAt());
        assertNotNull(state.getUpdatedAt());
    }
}
