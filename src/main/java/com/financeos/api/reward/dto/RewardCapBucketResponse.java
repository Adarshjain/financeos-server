package com.financeos.api.reward.dto;

import com.financeos.domain.reward.CapWindow;
import com.financeos.domain.reward.CounterScope;
import com.financeos.domain.reward.RewardCapBucket;
import com.financeos.domain.reward.RewardType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RewardCapBucketResponse(
        UUID id,
        UUID accountId,
        String name,
        BigDecimal cap,
        RewardType rewardType,
        CapWindow windowType,
        CounterScope counterScope,
        int ruleCount,
        Instant createdAt,
        Instant updatedAt) {

    public static RewardCapBucketResponse from(RewardCapBucket bucket, int ruleCount) {
        return new RewardCapBucketResponse(
                bucket.getId(),
                bucket.getAccount().getId(),
                bucket.getName(),
                bucket.getCap(),
                bucket.getRewardType(),
                bucket.getWindowType(),
                bucket.getCounterScope() != null ? bucket.getCounterScope() : CounterScope.ACCOUNT,
                ruleCount,
                bucket.getCreatedAt(),
                bucket.getUpdatedAt());
    }
}
