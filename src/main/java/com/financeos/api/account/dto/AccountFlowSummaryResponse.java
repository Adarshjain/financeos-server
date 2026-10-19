package com.financeos.api.account.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Money in and out of an account over {@code from}..{@code to} (inclusive), the biggest categories
 * on each side, the average daily balance over the same window, and the interest credited in the
 * last completed calendar quarter ({@code interestFrom}..{@code interestTo}).
 */
public record AccountFlowSummaryResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal moneyIn,
        BigDecimal moneyOut,
        List<String> topInCategories,
        List<String> topOutCategories,
        @Nullable BigDecimal averageDailyBalance,
        BigDecimal interestLastQuarter,
        LocalDate interestFrom,
        LocalDate interestTo
) {
}
