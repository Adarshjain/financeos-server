package com.financeos.api.reward.dto;

import com.financeos.domain.transaction.TransactionChannel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record RewardRecommendationRequest(
        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than 0")
        BigDecimal amount,

        LocalDate date,
        Set<UUID> categoryIds,
        String mcc,
        String merchantText,
        TransactionChannel channel,
        Boolean isEmi,
        Boolean isIntl,
        List<UUID> accountIds
) {
}
