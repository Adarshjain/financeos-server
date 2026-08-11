package com.financeos.api.reward.dto;

import com.financeos.domain.reward.AccrualType;
import com.financeos.domain.reward.CapWindow;
import com.financeos.domain.reward.MilestoneBasis;
import com.financeos.domain.reward.MilestonePayoutType;
import com.financeos.domain.reward.MilestoneWindow;
import com.financeos.domain.reward.RewardType;
import com.financeos.domain.reward.RuleStacking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Rewards report for one account over a display range. Cap math is always evaluated
 * over FULL cap windows intersecting the range; only lines inside the range are summed.
 */
public record RewardReportResponse(
        Summary summary,
        List<RuleBreakdown> rules,
        List<MilestoneStatus> milestones,
        boolean cycleFallback,
        boolean anniversaryFallback) {

    /**
     * Monetary fields in rupees; points stay points (no cash valuation of points yet).
     * basisSpend = eligible debit spend net of refunds. grossValueInr = cashbackInr +
     * milestonesInr (cash only — points-paying milestones land in milestonesPts);
     * effectiveValueInr adds instant discounts and subtracts convenience fees.
     * The percentage rates therefore cover cash value only.
     */
    public record Summary(
            BigDecimal basisSpend,
            int transactionCount,
            int matchedCount,
            BigDecimal cashbackInr,
            BigDecimal points,
            BigDecimal milestonesInr,
            BigDecimal milestonesPts,
            BigDecimal grossValueInr,
            BigDecimal discounts,
            BigDecimal fees,
            BigDecimal effectiveValueInr,
            BigDecimal grossPct,
            BigDecimal effectivePct) {
    }

    /**
     * One milestone × one window instance intersecting the display range. A CASH_VALUE
     * payout is attributed to exactly one payoutDate (the counted window's end, or the
     * threshold-crossing date when the milestone says ON_ACHIEVEMENT) and is counted
     * into the summary only when that date falls inside the range — so month-by-month
     * reports never double-count it.
     */
    public record MilestoneStatus(
            UUID milestoneId,
            String name,
            MilestoneWindow windowType,
            LocalDate windowStart,
            LocalDate windowEnd,
            MilestoneBasis basis,
            BigDecimal threshold,
            BigDecimal minTxnAmount,
            BigDecimal progress,
            boolean achieved,
            MilestonePayoutType payoutType,
            RewardType rewardType,
            BigDecimal payoutValue,
            LocalDate payoutDate,
            boolean countedInSummary) {
    }

    /** Per-rule aggregates over the display range, plus current-window cap usage. */
    public record RuleBreakdown(
            UUID ruleId,
            String name,
            RuleStacking stacking,
            AccrualType accrualType,
            boolean activeInRange,
            int matchedCount,
            BigDecimal basisMatched,
            BigDecimal earned,
            String earnedUnit,
            CapStatus capStatus) {
    }

    /**
     * Usage of the rule's period cap in the window containing the range end.
     * sharedBucket is non-null when the cap is a bucket drained by several rules.
     */
    public record CapStatus(
            CapWindow window,
            BigDecimal cap,
            BigDecimal used,
            LocalDate windowStart,
            LocalDate windowEnd,
            boolean cycleFallback,
            String sharedBucket) {
    }
}
