package com.financeos.domain.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MessageFormatTest {

    @Test
    void phrasesDaysWithAnyVerb() {
        assertEquals("debits today", MessageFormat.inDays("debits", 0));
        assertEquals("debits today", MessageFormat.inDays("debits", -3));
        assertEquals("due tomorrow", MessageFormat.inDays("due", 1));
        assertEquals("debits in 5 days", MessageFormat.inDays("debits", 5));
        assertEquals("1 day", MessageFormat.days(1));
        assertEquals("12 days", MessageFormat.days(12));
    }

    @Test
    void formatsMoneyDatesAndPoints() {
        assertEquals("₹25,000", MessageFormat.money(new BigDecimal("25000")));
        assertEquals("₹12,34,567.50", MessageFormat.money(new BigDecimal("1234567.5")));
        assertEquals("-₹10", MessageFormat.money(new BigDecimal("-10")));
        assertEquals("₹—", MessageFormat.money(null));
        assertEquals("5 Oct", MessageFormat.date(LocalDate.of(2026, 10, 5)));
        assertEquals("—", MessageFormat.date(null));
        assertEquals("1200", MessageFormat.points(new BigDecimal("1199.6")));
    }
}
