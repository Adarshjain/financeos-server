package com.financeos.api.investment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * F&amp;O results of one financial year. {@code winRate} is the percentage (0-100, one decimal) of
 * trades with a positive P&amp;L, 0 with no trades. {@code winCount} is how many of them that is. {@code byMonth} has all 12 months, zero-filled.
 */
public record FnoSummaryResponse(
        int fy,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal realisedPnl,
        long tradeCount,
        BigDecimal winRate,
        BigDecimal totalCharges,
        List<MonthPnl> byMonth,
        List<UnderlyingPnl> byUnderlying,
        int winCount
) {
    /** {@code month} is yyyy-MM. */
    public record MonthPnl(String month, BigDecimal pnl) {}

    public record UnderlyingPnl(String underlying, BigDecimal pnl, long trades) {}
}
