package com.financeos.domain.notification.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReminderStagesTest {

    @Test
    void stageCodesFollowTheDaysLeftAndStayEmptyBeyondTheWindow() {
        assertNull(ReminderStages.stage(4));
        assertNull(ReminderStages.stage(30));
        assertEquals("D3", ReminderStages.stage(3));
        assertEquals("D2", ReminderStages.stage(2));
        assertEquals("D1", ReminderStages.stage(1));
        assertEquals("D0", ReminderStages.stage(0));
        assertEquals("OVERDUE", ReminderStages.stage(-1));
        assertEquals("OVERDUE", ReminderStages.stage(-45));
        assertNull(ReminderStages.stage(-46), "past the stale limit nothing is shown");
        assertTrue(ReminderStages.isOverdue("OVERDUE"));
        assertFalse(ReminderStages.isOverdue("D0"));
    }

    @Test
    void phrasesPerOffset() {
        assertEquals("due in 3 days", ReminderStages.phrase(3));
        assertEquals("due in 2 days", ReminderStages.phrase(2));
        assertEquals("due tomorrow", ReminderStages.phrase(1));
        assertEquals("due today", ReminderStages.phrase(0));
        assertEquals("overdue by 1 day", ReminderStages.phrase(-1));
        assertEquals("overdue by 12 days", ReminderStages.phrase(-12));
    }

    @Test
    void billTexts() {
        assertEquals("HDFC Regalia bill due tomorrow", ReminderStages.billTitle("HDFC Regalia", 1));
        assertEquals("HDFC Regalia bill overdue by 3 days", ReminderStages.billTitle("HDFC Regalia", -3));
        assertEquals("Card bill due today", ReminderStages.billTitle(null, 0));
        assertEquals("₹12,340 due 6 Nov", ReminderStages.billBody(new BigDecimal("12340"), LocalDate.of(2026, 11, 6)));
    }

    @Test
    void emiTexts() {
        assertEquals("Home loan EMI due in 3 days", ReminderStages.emiTitle("Home loan", 3));
        assertEquals("Home loan EMI overdue by 2 days", ReminderStages.emiTitle("Home loan", -2));
        assertEquals("₹25,000 due 6 Nov · #3 of 24", ReminderStages.emiBody(new BigDecimal("25000"), LocalDate.of(2026, 11, 6), 3, 24));
    }

    @Test
    void keysAreStablePerBillAndInstallment() {
        UUID id = UUID.randomUUID();
        assertEquals("bill:" + id, ReminderKeys.bill(id));
        assertEquals("emi:" + id + ":7", ReminderKeys.emi(id, 7));
    }
}
