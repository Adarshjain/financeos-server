package com.financeos.api.reward.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Full milestone definition — POST creates, PUT overwrites (accountId ignored on PUT). */
public record RewardMilestoneRequest(
        UUID accountId,
        UUID cardholderId,
        @NotBlank(message = "Milestone name is required") String name,
        @NotBlank(message = "Window is required") String windowType,
        @NotBlank(message = "Basis is required") String basis,
        @NotNull(message = "Threshold is required") BigDecimal threshold,
        BigDecimal minTxnAmount,
        @NotBlank(message = "Payout type is required") String payoutType,
        /** CASH or POINTS; unset = the account's default reward type. */
        String rewardType,
        BigDecimal payoutValue,
        /** WINDOW_END (default) or ON_ACHIEVEMENT. */
        String payoutTiming,
        List<UUID> includeCategoryIds,
        List<String> includeMccs,
        List<UUID> excludeCategoryIds,
        List<String> excludeMccs,
        LocalDate activeFrom,
        LocalDate activeTo) {
}
