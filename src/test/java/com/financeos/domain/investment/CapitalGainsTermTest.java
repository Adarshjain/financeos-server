package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.domain.instrument.TaxClass;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Term of a realised lot by tax class: equity 12 months, other 24 months, specified debt from Apr 2023 slab. */
class CapitalGainsTermTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void equityIsLongAfter365Days() {
        assertEquals("short", CapitalGainsTerm.term(TaxClass.EQUITY_ORIENTED, d(2025, 1, 1), d(2026, 1, 1)), "365 days");
        assertEquals("long", CapitalGainsTerm.term(TaxClass.EQUITY_ORIENTED, d(2025, 1, 1), d(2026, 1, 2)), "366 days");
        assertEquals("short", CapitalGainsTerm.term(TaxClass.EQUITY_ORIENTED, d(2025, 1, 1), d(2025, 1, 1)));
    }

    @Test
    void otherIsLongOnlyAfter24Months() {
        assertEquals("short", CapitalGainsTerm.term(TaxClass.OTHER, d(2024, 3, 1), d(2025, 3, 2)), "a year is not enough");
        assertEquals("short", CapitalGainsTerm.term(TaxClass.OTHER, d(2024, 3, 1), d(2026, 3, 1)), "exactly 24 months");
        assertEquals("long", CapitalGainsTerm.term(TaxClass.OTHER, d(2024, 3, 1), d(2026, 3, 2)));
    }

    @Test
    void specifiedDebtFromApril2023IsAlwaysSlab() {
        assertEquals("slab", CapitalGainsTerm.term(TaxClass.SPECIFIED_DEBT, d(2023, 4, 1), d(2023, 4, 2)));
        assertEquals("slab", CapitalGainsTerm.term(TaxClass.SPECIFIED_DEBT, d(2023, 4, 1), d(2030, 1, 1)));
    }

    @Test
    void specifiedDebtBoughtEarlierFollowsThe24MonthRule() {
        assertEquals("short", CapitalGainsTerm.term(TaxClass.SPECIFIED_DEBT, d(2023, 3, 31), d(2025, 3, 31)));
        assertEquals("long", CapitalGainsTerm.term(TaxClass.SPECIFIED_DEBT, d(2023, 3, 31), d(2025, 4, 1)));
    }

    @Test
    void anUnknownTaxClassIsTreatedAsOther() {
        assertEquals("short", CapitalGainsTerm.term(null, d(2024, 1, 1), d(2025, 6, 1)));
        assertEquals("long", CapitalGainsTerm.term(null, d(2022, 1, 1), d(2025, 6, 1)));
    }

    @Test
    void onlyEquityBoughtBeforeFeb2018IsGrandfathered() {
        assertTrue(CapitalGainsTerm.grandfathered(TaxClass.EQUITY_ORIENTED, d(2018, 1, 31)));
        assertFalse(CapitalGainsTerm.grandfathered(TaxClass.EQUITY_ORIENTED, d(2018, 2, 1)));
        assertFalse(CapitalGainsTerm.grandfathered(TaxClass.OTHER, d(2015, 1, 1)));
        assertFalse(CapitalGainsTerm.grandfathered(TaxClass.SPECIFIED_DEBT, d(2015, 1, 1)));
        assertFalse(CapitalGainsTerm.grandfathered(null, d(2015, 1, 1)));
    }
}
