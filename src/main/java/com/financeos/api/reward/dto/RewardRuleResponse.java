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

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RewardRuleResponse(
        UUID id,
        UUID accountId,
        UUID cardId,
        CounterScope counterScope,
        String name,
        int priority,
        RuleStacking stacking,
        LocalDate activeFrom,
        LocalDate activeTo,
        List<CategoryResponse> categories,
        List<String> mccs,
        List<TransactionChannel> channels,
        List<DayOfWeek> daysOfWeek,
        String merchantPattern,
        RewardMerchantMatch merchantMatch,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        EmiTreatment emiTreatment,
        IntlTreatment intlTreatment,
        FeeTreatment feeTreatment,
        RewardType rewardType,
        AccrualType accrualType,
        BigDecimal percentRate,
        CashbackRounding rounding,
        BigDecimal slabSize,
        BigDecimal pointsPerSlab,
        Integer pointPrecision,
        CapWindow tierWindow,
        List<RewardTier> tiers,
        BigDecimal perTxnCap,
        BigDecimal periodCap,
        CapWindow capWindow,
        UUID capBucketId,
        String capBucketName,
        CapExhaustedBehavior onCapExhausted,
        Instant createdAt,
        Instant updatedAt) {

    /** tiers must be pre-parsed by the service (the entity stores them as JSON text). */
    public static RewardRuleResponse from(RewardRule rule, List<RewardTier> tiers) {
        List<CategoryResponse> categoryResponses = rule.getCategories().stream()
                .map(CategoryResponse::from)
                .toList();

        UUID cardId = rule.getCard() != null ? rule.getCard().getId() : null;

        return new RewardRuleResponse(
                rule.getId(),
                rule.getAccount().getId(),
                cardId,
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
