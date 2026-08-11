package com.financeos.api.reward.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Account-level reward config: the default currency (cash vs points) new rules pay
 * in. The anniversary anchor is part of the account itself (credit-card details),
 * not this config — it only appears read-only on the config response.
 */
public record RewardAccountConfigRequest(
        @NotNull(message = "Account ID is required") UUID accountId,
        /** CASH or POINTS; unset keeps the current default. */
        String defaultRewardType) {
}
