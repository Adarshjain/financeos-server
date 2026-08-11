package com.financeos.api.reward.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Full bucket definition — POST creates, PUT overwrites (accountId ignored on PUT). */
public record RewardCapBucketRequest(
        UUID accountId,
        @NotBlank(message = "Bucket name is required") String name,
        @NotNull(message = "Cap is required") BigDecimal cap,
        /** Unit of the cap: CASH (₹) or POINTS; unset = CASH. */
        String rewardType,
        @NotBlank(message = "Window is required") String windowType) {
}
