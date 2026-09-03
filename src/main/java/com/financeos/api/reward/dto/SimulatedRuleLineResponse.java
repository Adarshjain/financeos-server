package com.financeos.api.reward.dto;

import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RuleStacking;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

public record SimulatedRuleLineResponse(
        @Nullable UUID ruleId,
        @Nullable String ruleName,
        @Nullable RuleStacking stacking,
        BigDecimal earned,
        String earnedUnit,
        BigDecimal earnedValueInr,
        RewardLineReason reason,
        @Nullable SimulatedCapStatusResponse capStatus
) {
}
