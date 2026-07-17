package com.financeos.api.account.dto;

import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record CardCycleSummaryResponse(
        UUID statementId,
        LocalDate statementPeriodEnd,
        String statementVerdict,
        BigDecimal totalAmountDue,
        BigDecimal minimumAmountDue,
        LocalDate paymentDueDate,
        Integer daysUntilDue,
        Boolean isPastDue,
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
    public static CardCycleSummaryResponse empty() {
        return new CardCycleSummaryResponse(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
    }

    public static CardCycleSummaryResponse from(Statement statement) {
        if (statement == null) {
            return empty();
        }
        StatementCreditCardDetails details = statement.getCreditCardDetails();
        LocalDate dueDate = details != null ? details.getPaymentDueDate() : null;
        Integer daysUntil = null;
        Boolean isPast = null;
        if (dueDate != null) {
            long diff = ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
            daysUntil = (int) diff;
            isPast = diff < 0;
        }

        return new CardCycleSummaryResponse(
                statement.getId(),
                statement.getPeriodEnd(),
                statement.getVerdict() != null ? statement.getVerdict().name() : null,
                details != null ? details.getTotalAmountDue() : null,
                details != null ? details.getMinimumAmountDue() : null,
                dueDate,
                daysUntil,
                isPast,
                details != null ? details.getCreditLimit() : null,
                details != null ? details.getAvailableCreditLimit() : null,
                details != null ? details.getFinanceCharges() : null,
                details != null ? details.getFeesAndCharges() : null,
                details != null ? details.getPreviousBalance() : null,
                details != null ? details.getPaymentsReceived() : null,
                details != null ? details.getTotalPurchases() : null,
                details != null ? details.getRewardPointsBalance() : null,
                details != null ? details.getRewardPointsEarned() : null
        );
    }
}
