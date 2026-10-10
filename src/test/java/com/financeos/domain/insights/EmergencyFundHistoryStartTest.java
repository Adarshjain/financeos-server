package com.financeos.domain.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** History starts with the first full month: the first transaction's month only when it is on day 1–3. */
class EmergencyFundHistoryStartTest {

    @Test
    void aFirstTransactionOnDayOneToThreeKeepsItsMonth() {
        assertEquals(YearMonth.of(2026, 5), EmergencyFundService.historyStart(LocalDate.of(2026, 5, 1)));
        assertEquals(YearMonth.of(2026, 5), EmergencyFundService.historyStart(LocalDate.of(2026, 5, 3)));
    }

    @Test
    void aLaterFirstTransactionStartsHistoryTheNextMonth() {
        assertEquals(YearMonth.of(2026, 6), EmergencyFundService.historyStart(LocalDate.of(2026, 5, 4)));
        assertEquals(YearMonth.of(2026, 6), EmergencyFundService.historyStart(LocalDate.of(2026, 5, 31)));
        assertEquals(YearMonth.of(2027, 1), EmergencyFundService.historyStart(LocalDate.of(2026, 12, 15)));
    }
}
