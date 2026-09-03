package com.financeos.api.reward.dto;

import com.financeos.domain.reward.MilestoneBasis;
import com.financeos.domain.reward.MilestoneEligibility;
import com.financeos.domain.reward.MilestonePayoutTiming;
import com.financeos.domain.reward.MilestonePayoutType;
import com.financeos.domain.reward.MilestoneWindow;
import com.financeos.domain.reward.RewardMilestone;
import com.financeos.domain.reward.RewardType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RewardMilestoneResponse(
        UUID id,
        UUID accountId,
        @Nullable UUID cardholderId,
        String name,
        MilestoneWindow windowType,
        MilestoneBasis basis,
        BigDecimal threshold,
        @Nullable BigDecimal minTxnAmount,
        MilestonePayoutType payoutType,
        RewardType rewardType,
        @Nullable BigDecimal payoutValue,
        MilestonePayoutTiming payoutTiming,
        List<UUID> includeCategoryIds,
        List<String> includeMccs,
        List<UUID> excludeCategoryIds,
        List<String> excludeMccs,
        @Nullable LocalDate activeFrom,
        @Nullable LocalDate activeTo,
        Instant createdAt,
        Instant updatedAt) {

    public static RewardMilestoneResponse from(RewardMilestone milestone, MilestoneEligibility eligibility) {
        MilestoneEligibility e = eligibility != null ? eligibility : MilestoneEligibility.EMPTY;
        UUID cardholderId = milestone.getCardholder() != null ? milestone.getCardholder().getId() : null;
        return new RewardMilestoneResponse(
                milestone.getId(),
                milestone.getAccount().getId(),
                cardholderId,
                milestone.getName(),
                milestone.getWindowType(),
                milestone.getBasis(),
                milestone.getThreshold(),
                milestone.getMinTxnAmount(),
                milestone.getPayoutType(),
                milestone.getRewardType(),
                milestone.getPayoutValue(),
                milestone.getPayoutTiming(),
                e.includeCategoryIds(),
                e.includeMccs(),
                e.excludeCategoryIds(),
                e.excludeMccs(),
                milestone.getActiveFrom(),
                milestone.getActiveTo(),
                milestone.getCreatedAt(),
                milestone.getUpdatedAt());
    }
}
