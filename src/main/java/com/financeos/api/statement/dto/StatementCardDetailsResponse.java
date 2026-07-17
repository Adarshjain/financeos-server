package com.financeos.api.statement.dto;

import com.financeos.domain.statement.StatementCreditCardDetails;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StatementCardDetailsResponse(
        BigDecimal totalAmountDue,
        BigDecimal minimumAmountDue,
        LocalDate paymentDueDate,
        BigDecimal creditLimit,
        BigDecimal availableCreditLimit,
        BigDecimal financeCharges,
        BigDecimal feesAndCharges,
        BigDecimal previousBalance,
        BigDecimal paymentsReceived,
        BigDecimal totalPurchases,
        BigDecimal rewardPointsBalance,
        BigDecimal rewardPointsEarned
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
