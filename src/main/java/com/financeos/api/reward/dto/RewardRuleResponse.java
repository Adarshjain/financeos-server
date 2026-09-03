package com.financeos.api.reward.dto;

import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.reward.AccrualType;
import com.financeos.domain.reward.CapExhaustedBehavior;
import com.financeos.domain.reward.CapWindow;
import com.financeos.domain.reward.CashbackRounding;
import com.financeos.domain.reward.CounterScope;
import com.financeos.domain.reward.EmiTreatment;
import com.financeos.domain.reward.FeeTreatment;
import com.financeos.domain.reward.IntlTreatment;
import com.financeos.domain.reward.RewardMerchantMatch;
import com.financeos.domain.reward.RewardRule;
import com.financeos.domain.reward.RewardTier;
import com.financeos.domain.reward.RewardType;
import com.financeos.domain.reward.RuleStacking;
import com.financeos.domain.transaction.TransactionChannel;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RewardRuleResponse(
        UUID id,
        UUID accountId,
        @Nullable UUID cardholderId,
        CounterScope counterScope,
        String name,
        int priority,
        RuleStacking stacking,
        @Nullable LocalDate activeFrom,
        @Nullable LocalDate activeTo,
        List<CategoryResponse> categories,
        List<String> mccs,
        List<TransactionChannel> channels,
        List<DayOfWeek> daysOfWeek,
        @Nullable String merchantPattern,
        @Nullable RewardMerchantMatch merchantMatch,
        @Nullable BigDecimal minAmount,
        @Nullable BigDecimal maxAmount,
        @Nullable EmiTreatment emiTreatment,
        @Nullable IntlTreatment intlTreatment,
        @Nullable FeeTreatment feeTreatment,
        RewardType rewardType,
        AccrualType accrualType,
        @Nullable BigDecimal percentRate,
        @Nullable CashbackRounding rounding,
        @Nullable BigDecimal slabSize,
        @Nullable BigDecimal pointsPerSlab,
        @Nullable Integer pointPrecision,
        @Nullable CapWindow tierWindow,
        @Nullable List<RewardTier> tiers,
        @Nullable BigDecimal perTxnCap,
        @Nullable BigDecimal periodCap,
        @Nullable CapWindow capWindow,
        @Nullable UUID capBucketId,
        @Nullable String capBucketName,
        @Nullable CapExhaustedBehavior onCapExhausted,
        Instant createdAt,
        Instant updatedAt) {

    /** tiers must be pre-parsed by the service (the entity stores them as JSON text). */
    public static RewardRuleResponse from(RewardRule rule, List<RewardTier> tiers) {
        List<CategoryResponse> categoryResponses = rule.getCategories().stream()
                .map(CategoryResponse::from)
                .toList();

        UUID cardholderId = rule.getCardholder() != null ? rule.getCardholder().getId() : null;

        return new RewardRuleResponse(
                rule.getId(),
                rule.getAccount().getId(),
                cardholderId,
                rule.getCounterScope(),
                rule.getName(),
                rule.getPriority(),
                rule.getStacking(),
                rule.getActiveFrom(),
                rule.getActiveTo(),
                categoryResponses,
                rule.getMccs().stream().sorted().toList(),
                rule.getChannels().stream().sorted().toList(),
                rule.getDaysOfWeek().stream().sorted().toList(),
                rule.getMerchantPattern(),
                rule.getMerchantMatch(),
                rule.getMinAmount(),
                rule.getMaxAmount(),
                rule.getEmiTreatment(),
                rule.getIntlTreatment(),
                rule.getFeeTreatment(),
                rule.getRewardType(),
                rule.getAccrualType(),
                rule.getPercentRate(),
                rule.getRounding(),
                rule.getSlabSize(),
                rule.getPointsPerSlab(),
                rule.getPointPrecision(),
                rule.getTierWindow(),
                tiers,
                rule.getPerTxnCap(),
                rule.getPeriodCap(),
                rule.getCapWindow(),
                rule.getCapBucket() != null ? rule.getCapBucket().getId() : null,
                rule.getCapBucket() != null ? rule.getCapBucket().getName() : null,
                rule.getOnCapExhausted(),
                rule.getCreatedAt(),
                rule.getUpdatedAt());
    }
}
