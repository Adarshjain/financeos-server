package com.financeos.api.reward;

import com.financeos.api.reward.dto.RewardRecommendationRequest;
import com.financeos.api.reward.dto.RewardRecommendationResponse;
import com.financeos.domain.reward.RewardRecommendationService;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reward-recommendations")
public class RewardRecommendationController {

    private final RewardRecommendationService recommendationService;

    public RewardRecommendationController(RewardRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @PostMapping
    public ResponseEntity<RewardRecommendationResponse> getRecommendations(
            @Valid @RequestBody RewardRecommendationRequest request) {
        return ResponseEntity.ok(recommendationService.recommend(request));
    }
}
