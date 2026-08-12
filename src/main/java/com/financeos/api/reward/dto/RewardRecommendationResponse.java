package com.financeos.api.reward.dto;

import java.util.List;

public record RewardRecommendationResponse(
        RewardRecommendationRequest input,
        List<RewardCardRecommendationResponse> recommendations
) {
}
