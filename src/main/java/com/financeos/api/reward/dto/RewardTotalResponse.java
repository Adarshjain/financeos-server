package com.financeos.api.reward.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One credit card's reward total over its current anniversary year (calendar year when the
 * card has no anniversary, flagged by calendarYearFallback). points and grossValueInr equal
 * the report summary for the same from/to window.
 */
public record RewardTotalResponse(
        UUID accountId,
        LocalDate from,
        LocalDate to,
        boolean calendarYearFallback,
        BigDecimal points,
        @Nullable BigDecimal grossValueInr) {
}
