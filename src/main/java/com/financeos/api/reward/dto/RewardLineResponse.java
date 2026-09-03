package com.financeos.api.reward.dto;

import com.financeos.domain.reward.AccrualType;
import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RuleStacking;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One reward line: what a single transaction earned under a single rule, and why. */
public record RewardLineResponse(
        UUID transactionId,
        LocalDate transactionDate,
        LocalDate effectiveDate,
        @Nullable String description,
        @Nullable String sourcedDescription,
        @Nullable String mcc,
        @Nullable com.financeos.domain.transaction.TransactionChannel channel,
        BigDecimal amount,
        BigDecimal basis,
        @Nullable UUID ruleId,
        @Nullable String ruleName,
        @Nullable RuleStacking stacking,
        @Nullable AccrualType accrualType,
        BigDecimal earned,
        String earnedUnit,
        RewardLineReason reason,
        @Nullable UUID cardId,
        @Nullable String cardLabel) {

    public RewardLineResponse(
            UUID transactionId,
            LocalDate transactionDate,
            LocalDate effectiveDate,
            String description,
            String sourcedDescription,
            String mcc,
            com.financeos.domain.transaction.TransactionChannel channel,
            BigDecimal amount,
            BigDecimal basis,
            UUID ruleId,
            String ruleName,
            RuleStacking stacking,
            AccrualType accrualType,
            BigDecimal earned,
            String earnedUnit,
            RewardLineReason reason) {
        this(transactionId, transactionDate, effectiveDate, description, sourcedDescription, mcc, channel,
                amount, basis, ruleId, ruleName, stacking, accrualType, earned, earnedUnit, reason, null, null);
    }
}
