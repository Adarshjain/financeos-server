package com.financeos.api.reward.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record RewardCardRecommendationResponse(
        UUID accountId,
        String accountName,
        int rank,
        BigDecimal totalValueInr,
        BigDecimal guaranteedValueInr,
        BigDecimal milestoneValueInr,
        BigDecimal effectiveRatePct,
        /** CONFIG when the card has its own point valuation, DEFAULT when the fallback was used. */
        String pointValueSource,
        BigDecimal pointValueInr,
        /** Whether any points were actually converted to ₹ for this card — the fallback only misleads when true. */
        boolean pointsValued,
        List<SimulatedRuleLineResponse> ruleLines,
        List<SimulatedMilestoneResponse> milestones,
        boolean noRulesConfigured,
        boolean cycleFallback,
        boolean anniversaryFallback
) {
}
