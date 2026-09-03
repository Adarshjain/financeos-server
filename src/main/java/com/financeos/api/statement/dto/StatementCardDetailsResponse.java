package com.financeos.api.statement.dto;

import com.financeos.domain.statement.StatementCreditCardDetails;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StatementCardDetailsResponse(
        @Nullable BigDecimal totalAmountDue,
        @Nullable BigDecimal minimumAmountDue,
        @Nullable LocalDate paymentDueDate,
        @Nullable BigDecimal creditLimit,
        @Nullable BigDecimal availableCreditLimit,
        @Nullable BigDecimal financeCharges,
        @Nullable BigDecimal feesAndCharges,
        @Nullable BigDecimal previousBalance,
        @Nullable BigDecimal paymentsReceived,
        @Nullable BigDecimal totalPurchases,
        @Nullable BigDecimal rewardPointsBalance,
        @Nullable BigDecimal rewardPointsEarned
) {
    public static StatementCardDetailsResponse from(StatementCreditCardDetails details) {
        if (details == null) return null;
        return new StatementCardDetailsResponse(
                details.getTotalAmountDue(),
                details.getMinimumAmountDue(),
                details.getPaymentDueDate(),
                details.getCreditLimit(),
                details.getAvailableCreditLimit(),
                details.getFinanceCharges(),
                details.getFeesAndCharges(),
                details.getPreviousBalance(),
                details.getPaymentsReceived(),
                details.getTotalPurchases(),
                details.getRewardPointsBalance(),
                details.getRewardPointsEarned()
        );
    }
}
