package com.financeos.api.lending.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Lendings tiles: what is owed each way and what falls due by the end of the current month. */
public record CounterpartySummaryResponse(
        BigDecimal owedToYou,
        long owedToYouCount,
        /** Positive number. */
        BigDecimal youOwe,
        long youOweCount,
        Bucket dueThisMonth,
        Bucket expectedBack
) {
    public record Bucket(BigDecimal amount, long count, @Nullable String nextName, @Nullable LocalDate nextDate) {
    }
}
