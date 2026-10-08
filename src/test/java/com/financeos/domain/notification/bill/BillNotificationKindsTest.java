package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BillNotificationKindsTest {

    @Test
    void sequenceRanksReceivedThenDueByUrgencyThenOverdueThenPaid() {
        assertTrue(BillNotificationKinds.rank(null) < BillNotificationKinds.rank("RECEIVED"));
        assertEquals(BillNotificationKinds.rank("RECEIVED"), BillNotificationKinds.rank("DUE_MISSING"));
        assertTrue(BillNotificationKinds.isLater("DUE_7", "RECEIVED"));
        assertTrue(BillNotificationKinds.isLater("DUE_3", "DUE_7"));
        assertTrue(BillNotificationKinds.isLater("DUE_1", "DUE_3"));
        assertTrue(BillNotificationKinds.isLater("DUE_0", "DUE_1"));
        assertTrue(BillNotificationKinds.isLater("OVERDUE", "DUE_0"));
        assertTrue(BillNotificationKinds.isLater("PAID", "OVERDUE"));
        assertFalse(BillNotificationKinds.isLater("DUE_7", "DUE_3"));
        assertFalse(BillNotificationKinds.isLater("RECEIVED", "RECEIVED"));
    }

    @Test
    void dueKindsRoundTripTheirOffset() {
        assertEquals("DUE_7", BillNotificationKinds.dueIn(7));
        assertEquals("DUE_0", BillNotificationKinds.dueIn(0));
        assertEquals(7, BillNotificationKinds.dueOffset("DUE_7"));
        assertEquals(-1, BillNotificationKinds.dueOffset("DUE_MISSING"));
        assertEquals(-1, BillNotificationKinds.dueOffset("OVERDUE"));
        assertEquals(-1, BillNotificationKinds.dueOffset("DUE_x"));
        assertTrue(BillNotificationKinds.isDue("DUE_14"));
        assertFalse(BillNotificationKinds.isDue("DUE_MISSING"));
        assertThrows(IllegalArgumentException.class, () -> BillNotificationKinds.dueIn(-1));
        assertThrows(IllegalArgumentException.class, () -> BillNotificationKinds.dueIn(1000));
    }

    @Test
    void unknownKindsRankBeforeEverythingSoTheyNeverBlockASend() {
        assertEquals(BillNotificationKinds.RANK_NONE, BillNotificationKinds.rank("SOMETHING_ELSE"));
        assertTrue(BillNotificationKinds.isLater("RECEIVED", "SOMETHING_ELSE"));
    }
}
