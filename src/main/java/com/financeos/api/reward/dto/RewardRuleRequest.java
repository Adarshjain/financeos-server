package com.financeos.api.reward.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Full rule definition — used by both create (POST) and update (PUT, full overwrite).
 * PUT ignores accountId (a rule never moves between accounts).
 * Enum-like fields travel as strings and are parsed with friendly errors server-side.
 */
public record RewardRuleRequest(
        UUID accountId,
        UUID cardholderId,
        @Nullable String counterScope,

        @NotBlank(message = "Rule name is required") String name,

        @NotNull(message = "Priority is required") Integer priority,

        @Nullable String stacking,

        @Nullable LocalDate activeFrom,
        @Nullable LocalDate activeTo,

        List<UUID> categoryIds,
        List<String> mccs,
        List<String> channels,
        List<String> daysOfWeek,
        String merchantPattern,
        @Nullable String merchantMatch,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @Nullable String emiTreatment,
        @Nullable String intlTreatment,
        /** INCLUDE (default) or EXCLUDE_FEE — nets the labeled surcharge out of the basis. */
        @Nullable String feeTreatment,

        /** CASH or POINTS; unset = the account's default reward type. */
        @Nullable String rewardType,
        @NotBlank(message = "Accrual type is required") String accrualType,
        BigDecimal percentRate,
        @Nullable String rounding,
        BigDecimal slabSize,
        BigDecimal pointsPerSlab,
        Integer pointPrecision,
        @Nullable String tierWindow,
        List<TierRequest> tiers,

        BigDecimal perTxnCap,
        BigDecimal periodCap,
        @Nullable String capWindow,
        UUID capBucketId,
        @Nullable String onCapExhausted) {

    /** One marginal-rate tranche; upTo null = open-ended final tranche. */
    public record TierRequest(BigDecimal upTo, BigDecimal rate) {
    }
}
