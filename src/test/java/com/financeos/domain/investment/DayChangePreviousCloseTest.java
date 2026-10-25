package com.financeos.domain.investment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Day change against the Yahoo feed row's own previous_close, and the unchanged two-row logic otherwise. */
class DayChangePreviousCloseTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 19);
    private static final Instant YESTERDAY_AT = Instant.parse("2026-10-19T10:00:00Z");

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static DayChange.PricePoint feedToday(String close, String previousClose) {
        return new DayChange.PricePoint(TODAY, bd(close), null, previousClose == null ? null : bd(previousClose));
    }

    @Test
    void usesTheFeedPreviousCloseEvenWhenThePriorRowDiffers() {
        List<DayChange.PricePoint> rows = List.of(feedToday("110", "100"),
                new DayChange.PricePoint(YESTERDAY, bd("90"), YESTERDAY_AT));
        DayChange c = DayChange.forPosition(bd("10"), bd("110"), TODAY, rows, TODAY, null);
        assertEquals(0, bd("100").compareTo(c.previousClose()));
        assertEquals(0, bd("100.00").compareTo(c.dayChange()));
        assertEquals(0, bd("10.00").compareTo(c.dayChangePct()));
        assertEquals(YESTERDAY, c.previousCloseAsOf());
        assertEquals(YESTERDAY_AT, c.previousCloseAt());
    }

    @Test
    void worksWithASingleRowAndLeavesPreviousCloseDatesNull() {
        DayChange c = DayChange.forPosition(bd("10"), bd("95"), TODAY, List.of(feedToday("95", "100")), TODAY, null);
        assertEquals(0, bd("-50.00").compareTo(c.dayChange()));
        assertEquals(0, bd("-5.00").compareTo(c.dayChangePct()));
        assertNull(c.previousCloseAsOf());
        assertNull(c.previousCloseAt());
    }

    @Test
    void withoutAFeedPreviousCloseTheLatestTwoRowsApply() {
        List<DayChange.PricePoint> rows = List.of(feedToday("110", null),
                new DayChange.PricePoint(YESTERDAY, bd("90"), YESTERDAY_AT));
        DayChange c = DayChange.forPosition(bd("10"), bd("110"), TODAY, rows, TODAY, null);
        assertEquals(0, bd("90").compareTo(c.previousClose()));
        assertEquals(0, bd("200.00").compareTo(c.dayChange()));
        assertEquals(DayChange.NONE, DayChange.forPosition(bd("10"), bd("110"), TODAY, List.of(feedToday("110", null)), TODAY, null));
    }

    @Test
    void aManualRowWinningTodayUsesTheCurrentLogic() {
        // The viewer's MANUAL row replaced the feed row of today: it carries no previous_close.
        List<DayChange.PricePoint> rows = List.of(new DayChange.PricePoint(TODAY, bd("120"), null, null),
                new DayChange.PricePoint(YESTERDAY, bd("100"), YESTERDAY_AT, bd("99")));
        DayChange c = DayChange.forPosition(bd("1"), bd("120"), TODAY, rows, TODAY, null);
        assertEquals(0, bd("100").compareTo(c.previousClose()));
        assertEquals(0, bd("20.00").compareTo(c.dayChange()));
    }

    @Test
    void aStaleFeedRowHasNoDayChange() {
        List<DayChange.PricePoint> rows = List.of(new DayChange.PricePoint(TODAY.minusDays(10), bd("110"), null, bd("100")));
        assertEquals(DayChange.NONE, DayChange.forPosition(bd("1"), bd("110"), TODAY.minusDays(10), rows, TODAY, null));
    }

    @Test
    void nativeRowsMapPreviousCloseWithOrWithoutTheColumn() {
        Object[] withPrev = {"id", java.sql.Date.valueOf(TODAY), bd("110"), Timestamp.from(YESTERDAY_AT), bd("100.5")};
        DayChange.PricePoint p = InvestmentService.pricePoint(withPrev);
        assertEquals(TODAY, p.asOf());
        assertEquals(YESTERDAY_AT, p.at());
        assertEquals(0, bd("100.5").compareTo(p.feedPreviousClose()));

        Object[] nullPrev = {"id", java.sql.Date.valueOf(TODAY), bd("110"), null, null};
        assertNull(InvestmentService.pricePoint(nullPrev).feedPreviousClose());
        Object[] legacy = {"id", java.sql.Date.valueOf(TODAY), bd("110"), null};
        assertNull(InvestmentService.pricePoint(legacy).feedPreviousClose());
        Object[] stringPrev = {"id", java.sql.Date.valueOf(TODAY), "110", null, "100"};
        assertEquals(0, bd("100").compareTo(InvestmentService.pricePoint(stringPrev).feedPreviousClose()));
    }

    private static com.financeos.domain.instrument.corporateaction.CorporateAction action(
            com.financeos.domain.instrument.corporateaction.CorporateActionType type, Integer from, Integer to, LocalDate ex) {
        var ca = new com.financeos.domain.instrument.corporateaction.CorporateAction();
        ca.setType(type);
        ca.setRatioFrom(from);
        ca.setRatioTo(to);
        ca.setExDate(ex);
        return ca;
    }

    @Test
    void aSplitExTodayUsesTheOldRescalePathNotTheFeedPreviousClose() {
        List<DayChange.PricePoint> rows = List.of(feedToday("55", "100"),
                new DayChange.PricePoint(YESTERDAY, bd("100"), YESTERDAY_AT));
        DayChange c = DayChange.forPosition(bd("20"), bd("55"), TODAY, rows, TODAY,
                List.of(action(com.financeos.domain.instrument.corporateaction.CorporateActionType.split, 1, 2, TODAY)));
        assertEquals(0, bd("50.0000").compareTo(c.previousClose()));
        assertEquals(0, bd("100.00").compareTo(c.dayChange()));
    }

    @Test
    void aDemergerInTheWindowGivesNone() {
        List<DayChange.PricePoint> rows = List.of(feedToday("55", "100"),
                new DayChange.PricePoint(YESTERDAY, bd("100"), YESTERDAY_AT));
        assertEquals(DayChange.NONE, DayChange.forPosition(bd("20"), bd("55"), TODAY, rows, TODAY,
                List.of(action(com.financeos.domain.instrument.corporateaction.CorporateActionType.demerger, null, null, TODAY))));
        // With no prior row the window is lastAsOf - 7 days.
        assertEquals(DayChange.NONE, DayChange.forPosition(bd("20"), bd("55"), TODAY, List.of(feedToday("55", "100")), TODAY,
                List.of(action(com.financeos.domain.instrument.corporateaction.CorporateActionType.demerger, null, null, TODAY.minusDays(3)))));
    }

    @Test
    void anActionOutsideTheWindowKeepsTheFeedPreviousClose() {
        List<DayChange.PricePoint> rows = List.of(feedToday("110", "100"),
                new DayChange.PricePoint(YESTERDAY, bd("90"), YESTERDAY_AT));
        DayChange c = DayChange.forPosition(bd("10"), bd("110"), TODAY, rows, TODAY,
                List.of(action(com.financeos.domain.instrument.corporateaction.CorporateActionType.demerger, null, null, YESTERDAY)));
        assertEquals(0, bd("100").compareTo(c.previousClose()));
    }
}
