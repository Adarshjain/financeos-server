package com.financeos.core.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FinancialYearTest {

    @Test
    void thirtyFirstMarchBelongsToTheYearThatStartedTheApril() {
        FinancialYear fy = FinancialYear.containing(LocalDate.of(2027, 3, 31));
        assertEquals(LocalDate.of(2026, 4, 1), fy.start());
        assertEquals(LocalDate.of(2027, 3, 31), fy.end());
    }

    @Test
    void firstAprilStartsANewYear() {
        FinancialYear fy = FinancialYear.containing(LocalDate.of(2027, 4, 1));
        assertEquals(LocalDate.of(2027, 4, 1), fy.start());
        assertEquals(LocalDate.of(2028, 3, 31), fy.end());
    }

    @Test
    void midYearAndJanuary() {
        assertEquals(LocalDate.of(2026, 4, 1), FinancialYear.containing(LocalDate.of(2026, 10, 19)).start());
        assertEquals(LocalDate.of(2025, 4, 1), FinancialYear.containing(LocalDate.of(2026, 1, 5)).start());
    }
}
