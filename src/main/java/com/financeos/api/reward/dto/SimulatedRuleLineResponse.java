package com.financeos.api.reward.dto;

import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RuleStacking;

import java.math.BigDecimal;
import java.util.UUID;

public record SimulatedRuleLineResponse(
        UUID ruleId,
        String ruleName,
        RuleStacking stacking,
        BigDecimal earned,
        String earnedUnit,
        BigDecimal earnedValueInr,
        RewardLineReason reason,
        SimulatedCapStatusResponse capStatus
) {
}
