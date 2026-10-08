package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReminderSequenceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void applicableKindPicksTheSmallestReachedOffsetOrOverdue() {
        assertEquals("DUE_7", ReminderSequence.applicableKind(5, List.of(7, 3, 1, 0)));
        assertEquals("DUE_0", ReminderSequence.applicableKind(0, List.of(7, 3, 1, 0)));
        assertEquals("OVERDUE", ReminderSequence.applicableKind(-1, List.of(0)));
        assertNull(ReminderSequence.applicableKind(8, List.of(7, 3, 1, 0)));
        assertNull(ReminderSequence.applicableKind(0, List.of()));
    }

    @Test
    void shouldSendRespectsTheSequenceAndTheRenagInterval() {
        assertFalse(ReminderSequence.shouldSend(null, null, null, TODAY, 7));
        assertTrue(ReminderSequence.shouldSend("DUE_0", null, null, TODAY, 7));
        assertFalse(ReminderSequence.shouldSend("DUE_0", "DUE_0", TODAY.minusDays(1), TODAY, 7));
        assertTrue(ReminderSequence.shouldSend("OVERDUE", "DUE_0", TODAY.minusDays(1), TODAY, 7));
        assertFalse(ReminderSequence.shouldSend("OVERDUE", "OVERDUE", TODAY.minusDays(6), TODAY, 7));
        assertTrue(ReminderSequence.shouldSend("OVERDUE", "OVERDUE", TODAY.minusDays(7), TODAY, 7));
        assertTrue(ReminderSequence.shouldSend("OVERDUE", "OVERDUE", TODAY.minusDays(3), TODAY, 3), "interval is per producer");
        assertFalse(ReminderSequence.shouldSend("DUE_3", "OVERDUE", TODAY, TODAY, 7), "never walks backwards");
    }
}
