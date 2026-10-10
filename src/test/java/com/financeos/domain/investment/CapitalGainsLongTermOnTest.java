package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.financeos.domain.instrument.TaxClass;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The first long-term sell date agrees with {@link CapitalGainsTerm#term} for every tax class. */
class CapitalGainsLongTermOnTest {

    private static final LocalDate BUY = LocalDate.of(2025, 1, 15);

    private static void assertBoundary(TaxClass taxClass, LocalDate buy) {
        LocalDate on = CapitalGainsTerm.longTermOn(taxClass, buy);
        assertEquals(CapitalGainsTerm.SHORT, CapitalGainsTerm.term(taxClass, buy, on.minusDays(1)), taxClass + " day before");
        assertEquals(CapitalGainsTerm.LONG, CapitalGainsTerm.term(taxClass, buy, on), taxClass + " on the day");
    }

    @Test
    void equityTurnsLong366DaysOn() {
        assertEquals(LocalDate.of(2026, 1, 16), CapitalGainsTerm.longTermOn(TaxClass.EQUITY_ORIENTED, BUY));
        assertBoundary(TaxClass.EQUITY_ORIENTED, BUY);
        assertBoundary(TaxClass.EQUITY_ORIENTED, LocalDate.of(2024, 2, 29));
    }

    @Test
    void otherTurnsLongTheDayAfterTwentyFourMonths() {
        assertEquals(LocalDate.of(2027, 1, 16), CapitalGainsTerm.longTermOn(TaxClass.OTHER, BUY));
        assertBoundary(TaxClass.OTHER, BUY);
        assertBoundary(null, BUY);
    }

    @Test
    void specifiedDebtIsSlabFromApril2023AndOtherwiseLikeOther() {
        assertNull(CapitalGainsTerm.longTermOn(TaxClass.SPECIFIED_DEBT, LocalDate.of(2023, 4, 1)));
        assertEquals(LocalDate.of(2025, 4, 1), CapitalGainsTerm.longTermOn(TaxClass.SPECIFIED_DEBT, LocalDate.of(2023, 3, 31)));
        assertBoundary(TaxClass.SPECIFIED_DEBT, LocalDate.of(2023, 3, 31));
    }
}
