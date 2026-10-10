package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.financeos.domain.instrument.TaxClass;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Long term means held MORE than 12 (equity) / 24 (other) calendar months — not "more than 365
 * days", which a leap year turns into exactly twelve months.
 */
class CapitalGainsLeapYearTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void exactlyTwelveMonthsAcrossALeapDayIsStillShort() {
        // 366 days, but only 12 months.
        assertEquals("short", CapitalGainsTerm.term(TaxClass.EQUITY_ORIENTED, d(2023, 3, 1), d(2024, 3, 1)));
        assertEquals("long", CapitalGainsTerm.term(TaxClass.EQUITY_ORIENTED, d(2023, 3, 1), d(2024, 3, 2)));
        assertEquals(d(2024, 3, 2), CapitalGainsTerm.longTermOn(TaxClass.EQUITY_ORIENTED, d(2023, 3, 1)));
    }

    @Test
    void boughtOnALeapDay() {
        // 2024-02-29 + 12 months = 2025-02-28.
        assertEquals("short", CapitalGainsTerm.term(TaxClass.EQUITY_ORIENTED, d(2024, 2, 29), d(2025, 2, 28)));
        assertEquals("long", CapitalGainsTerm.term(TaxClass.EQUITY_ORIENTED, d(2024, 2, 29), d(2025, 3, 1)));
        assertEquals(d(2025, 3, 1), CapitalGainsTerm.longTermOn(TaxClass.EQUITY_ORIENTED, d(2024, 2, 29)));
        // 2024-02-29 + 24 months = 2026-02-28.
        assertEquals("short", CapitalGainsTerm.term(TaxClass.OTHER, d(2024, 2, 29), d(2026, 2, 28)));
        assertEquals("long", CapitalGainsTerm.term(TaxClass.OTHER, d(2024, 2, 29), d(2026, 3, 1)));
        assertEquals(d(2026, 3, 1), CapitalGainsTerm.longTermOn(TaxClass.OTHER, d(2024, 2, 29)));
    }

    @Test
    void twentyFourMonthsAcrossALeapYearIsStillShortForOther() {
        assertEquals("short", CapitalGainsTerm.term(TaxClass.OTHER, d(2023, 3, 1), d(2025, 3, 1)));
        assertEquals("long", CapitalGainsTerm.term(TaxClass.OTHER, d(2023, 3, 1), d(2025, 3, 2)));
    }

    @Test
    void sellingOnTheLongTermOnDateIsLong() {
        for (LocalDate buy : new LocalDate[]{d(2023, 3, 1), d(2024, 2, 29), d(2023, 1, 31), d(2024, 12, 31)}) {
            for (TaxClass tc : new TaxClass[]{TaxClass.EQUITY_ORIENTED, TaxClass.OTHER}) {
                LocalDate on = CapitalGainsTerm.longTermOn(tc, buy);
                assertEquals("long", CapitalGainsTerm.term(tc, buy, on), tc + " " + buy);
                assertEquals("short", CapitalGainsTerm.term(tc, buy, on.minusDays(1)), tc + " " + buy);
            }
        }
    }
}
