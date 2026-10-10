package com.financeos.domain.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import com.financeos.domain.investment.DayChange.PricePoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link DayChange#forPosition}: only a current price series counts, and a split or bonus between
 * the two closes rescales the previous close instead of showing a fake drop; anything else in that
 * window makes the move unknowable.
 */
class DayChangeGuardsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final LocalDate LAST = LocalDate.of(2026, 10, 8);
    private static final LocalDate PREV = LocalDate.of(2026, 10, 7);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static List<PricePoint> closes(LocalDate last, String lastClose, LocalDate prev, String prevClose) {
        return List.of(new PricePoint(last, d(lastClose)), new PricePoint(prev, d(prevClose)));
    }

    private static CorporateAction ca(CorporateActionType type, Integer from, Integer to, LocalDate exDate) {
        CorporateAction ca = new CorporateAction();
        ca.setType(type);
        ca.setRatioFrom(from);
        ca.setRatioTo(to);
        ca.setExDate(exDate);
        return ca;
    }

    // ------------------------------------------------------------------ splits and bonuses

    @Test
    void aSplitBetweenTheClosesHalvesThePreviousCloseInsteadOfShowingMinusFiftyPercent() {
        // 10 shares became 20 on the ex-date; the price went 1,000 → 510.
        DayChange change = DayChange.forPosition(d("20"), d("510"), LAST, closes(LAST, "510", PREV, "1000"), TODAY,
                List.of(ca(CorporateActionType.split, 1, 2, LAST)));

        assertEquals(0, d("500").compareTo(change.previousClose()));
        assertEquals(PREV, change.previousCloseAsOf());
        assertEquals(d("200.00"), change.dayChange());        // 20 × (510 − 500)
        assertEquals(d("2.00"), change.dayChangePct());
    }

    @Test
    void aBonusScalesThePreviousCloseByItsRatio() {
        // 1:2 bonus (2 held → 3): previous 300 → 200.
        DayChange change = DayChange.forPosition(d("30"), d("198"), LAST, closes(LAST, "198", PREV, "300"), TODAY,
                List.of(ca(CorporateActionType.bonus, 2, 3, LAST)));
        assertEquals(0, d("200").compareTo(change.previousClose()));
        assertEquals(d("-60.00"), change.dayChange());
        assertEquals(d("-1.00"), change.dayChangePct());
    }

    @Test
    void anActionOnThePreviousDayOrAfterTheLastCloseIsIgnored() {
        List<CorporateAction> outside = List.of(
                ca(CorporateActionType.split, 1, 2, PREV),                 // already in the previous close
                ca(CorporateActionType.split, 1, 10, TODAY),               // not in either close yet
                ca(CorporateActionType.merger, 1, 1, PREV.minusDays(1)));
        DayChange change = DayChange.forPosition(d("10"), d("120"), LAST, closes(LAST, "120", PREV, "100"), TODAY, outside);
        assertEquals(0, d("100").compareTo(change.previousClose()));
        assertEquals(d("200.00"), change.dayChange());
    }

    @Test
    void twoActionsInTheWindowCompound() {
        LocalDate prev = LAST.minusDays(3);
        DayChange change = DayChange.forPosition(d("40"), d("26"), LAST, closes(LAST, "26", prev, "100"), TODAY,
                List.of(ca(CorporateActionType.split, 1, 2, prev.plusDays(1)),
                        ca(CorporateActionType.bonus, 1, 2, LAST)));
        assertEquals(0, d("25").compareTo(change.previousClose()));
        assertEquals(d("40.00"), change.dayChange());
    }

    @Test
    void aMergerDemergerOrUnusableRatioInTheWindowMeansNoDayChange() {
        for (CorporateAction ambiguous : List.of(
                ca(CorporateActionType.merger, 1, 2, LAST),
                ca(CorporateActionType.demerger, 2, 1, LAST),
                ca(CorporateActionType.split, null, 2, LAST),
                ca(CorporateActionType.bonus, 1, 0, LAST))) {
            DayChange change = DayChange.forPosition(d("10"), d("120"), LAST, closes(LAST, "120", PREV, "100"), TODAY,
                    List.of(ambiguous));
            assertSame(DayChange.NONE, change, ambiguous.getType() + " " + ambiguous.getRatioFrom());
        }
    }

    // ------------------------------------------------------------------ stale series

    @Test
    void theLatestCloseMustBeAtMostFourDaysOld() {
        LocalDate fourDaysAgo = TODAY.minusDays(4);
        DayChange current = DayChange.forPosition(d("10"), d("120"), fourDaysAgo,
                closes(fourDaysAgo, "120", fourDaysAgo.minusDays(1), "100"), TODAY, null);
        assertEquals(d("200.00"), current.dayChange());

        LocalDate fiveDaysAgo = TODAY.minusDays(5);
        assertSame(DayChange.NONE, DayChange.forPosition(d("10"), d("120"), fiveDaysAgo,
                closes(fiveDaysAgo, "120", fiveDaysAgo.minusDays(1), "100"), TODAY, null));
    }

    @Test
    void thePreviousCloseMustBeWithinSevenDaysOfTheLatest() {
        DayChange week = DayChange.forPosition(d("10"), d("120"), LAST, closes(LAST, "120", LAST.minusDays(7), "100"),
                TODAY, List.of());
        assertEquals(d("200.00"), week.dayChange());
        assertSame(DayChange.NONE, DayChange.forPosition(d("10"), d("120"), LAST,
                closes(LAST, "120", LAST.minusDays(8), "100"), TODAY, List.of()));
    }

    @Test
    void withoutTwoClosesThereIsNoDayChange() {
        assertSame(DayChange.NONE, DayChange.forPosition(d("10"), d("120"), LAST,
                List.of(new PricePoint(LAST, d("120"))), TODAY, null));
        assertSame(DayChange.NONE, DayChange.forPosition(d("10"), d("120"), null, null, TODAY, null));
        assertNull(DayChange.forPosition(d("0"), d("120"), LAST, closes(LAST, "120", PREV, "100"), TODAY, null)
                .dayChange(), "closed position");
    }
}
