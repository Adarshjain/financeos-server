package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.domain.report.definition.Granularity;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BucketLabelTest {

    @Test
    void formatsDayMonthQuarterYear() {
        assertEquals("15 Jun 26", TransactionQueryBuilder.bucketLabel(LocalDate.of(2026, 6, 15), Granularity.DAY));
        assertEquals("Jun 26", TransactionQueryBuilder.bucketLabel(LocalDate.of(2026, 6, 1), Granularity.MONTH));
        assertEquals("Q2 26", TransactionQueryBuilder.bucketLabel(LocalDate.of(2026, 4, 1), Granularity.QUARTER));
        assertEquals("Q4 26", TransactionQueryBuilder.bucketLabel(LocalDate.of(2026, 12, 1), Granularity.QUARTER));
        assertEquals("2026", TransactionQueryBuilder.bucketLabel(LocalDate.of(2026, 1, 1), Granularity.YEAR));
    }

    @Test
    void formatsIsoWeek() {
        String week = TransactionQueryBuilder.bucketLabel(LocalDate.of(2026, 6, 15), Granularity.WEEK);
        assertTrue(week.matches("W\\d{1,2} 26"), "unexpected week label: " + week);
    }
}
