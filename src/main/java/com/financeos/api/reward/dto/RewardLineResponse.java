package com.financeos.api.reward.dto;

import com.financeos.domain.reward.AccrualType;
import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RuleStacking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One reward line: what a single transaction earned under a single rule, and why. */
public record RewardLineResponse(
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
}
