package com.financeos.api.reward.dto;

import org.springframework.lang.Nullable;

import com.financeos.domain.reward.MilestoneBasis;
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
 *
 * nearestCap, nextMilestone and lastMet describe the current reward year as of today: nearestCap is the
 * period cap (a rule's own, or a shared bucket) with the highest used/cap in the window containing
 * today (ties by soonest windowEnd, exhausted caps included), null without a capped active rule;
 * nextMilestone is the unmet milestone window ending today or later with the highest progress/threshold
 * (ties by soonest windowEnd); lastMet is the milestone achieved latest inside the reward year. Each is
 * null when none. nearestCap.unit is RUPEES or POINTS, as the report's earnedUnit.
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
        BigDecimal milestoneValueInr,
        @Nullable NearestCap nearestCap,
        @Nullable NextMilestone nextMilestone,
        @Nullable LastMet lastMet) {

    public RewardTotalResponse(UUID accountId, LocalDate from, LocalDate to, boolean calendarYearFallback,
            BigDecimal points, @Nullable BigDecimal grossValueInr, @Nullable BigDecimal pointValueInr,
            BigDecimal milestonePoints, BigDecimal milestoneValueInr) {
        this(accountId, from, to, calendarYearFallback, points, grossValueInr, pointValueInr, milestonePoints,
                milestoneValueInr, null, null, null);
    }

    public record NearestCap(String label, BigDecimal used, BigDecimal cap, String unit, LocalDate windowEnd) {
    }

    public record NextMilestone(String name, BigDecimal progress, BigDecimal threshold, MilestoneBasis basis,
            LocalDate windowEnd) {
    }

    public record LastMet(String name, LocalDate achievedOn) {
    }
}
