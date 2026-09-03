package com.financeos.api.reward.dto;

import jakarta.validation.constraints.NotNull;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Account-level reward config: the default currency (cash vs points) new rules pay
 * in, plus the conversion rate from points to INR. The anniversary anchor is part of
 * the account itself (credit-card details), not this config — it only appears read-only
 * on the config response.
 */
public record RewardAccountConfigRequest(
        @NotNull(message = "Account ID is required") UUID accountId,
        /** CASH or POINTS; unset keeps the current default. */
        @Nullable String defaultRewardType,
        /** Point valuation in INR (must be > 0 when set; null clears back to default fallback). */
        @Nullable BigDecimal pointValueInr) {
}

