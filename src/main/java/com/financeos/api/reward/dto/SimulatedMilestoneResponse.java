package com.financeos.api.reward.dto;

import com.financeos.domain.reward.MilestonePayoutType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SimulatedMilestoneResponse(
        UUID milestoneId,
        String name,
        LocalDate windowEnd,
        BigDecimal progress,
        BigDecimal threshold,
        BigDecimal remainingToThreshold,
        boolean crosses,
        @Nullable BigDecimal payoutInr,
        BigDecimal scoredValueInr,
        MilestonePayoutType payoutType
) {
}
