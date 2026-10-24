package com.financeos.api.reward.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One account's reward total over its current anniversary year (calendar year when it has no
 * anniversary, flagged by calendarYearFallback). Covers every credit card plus any other account
 * with reward rules. points and grossValueInr equal the report summary for the same from/to
 * window. pointValueInr is the account's configured point value (as GET /reward-config), null
 * when not set. milestonePoints and milestoneValueInr equal the report summary's milestonesPts and
 * milestonesInr for the same window (achieved CASH_VALUE milestones paid inside it): the card page's
 * earned points are points + milestonePoints. grossValueInr already includes milestoneValueInr (cash
 * milestones) and the valued points, so only the points need combining.
 */
public record RewardTotalResponse(
        UUID accountId,
        LocalDate from,
        LocalDate to,
        boolean calendarYearFallback,
        BigDecimal points,
        @Nullable BigDecimal grossValueInr,
        @Nullable BigDecimal pointValueInr,
        BigDecimal milestonePoints,
        BigDecimal milestoneValueInr) {
}
