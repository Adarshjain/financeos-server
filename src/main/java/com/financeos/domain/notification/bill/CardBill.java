package com.financeos.domain.notification.bill;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One credit card's current bill: the latest live statement plus everything the settlement
 * signals say about it. Never stored — the dashboard card and the notification tick both
 * build it through {@link CardBillService}, so they cannot disagree.
 */
public record CardBill(
        UUID accountId,
        String accountName,
        String last4,
        UUID statementId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate paymentDueDate,
        BigDecimal totalAmountDue,
        BigDecimal minimumAmountDue,
        BigDecimal paidAmount,
        BigDecimal remainingAmount,
        PaidSource paidSource,
        BillStatus status,
        Long daysUntilDue,
        LocalDate paidMarkedOn,
        List<PossiblePayment> possiblePayments,
        boolean muted,
        Instant statementCreatedAt,
        String lastNotifiedKind,
        LocalDate lastNotifiedOn,
        Digest digest
) {
    /** An unlinked credit after the period end: "looks like your payment — confirm?" Never auto-closes the bill. */
    public record PossiblePayment(UUID transactionId, LocalDate date, BigDecimal amount, String description) {
    }

    /** The statement-arrived summary. */
    public record Digest(
            BigDecimal totalPurchases,
            BigDecimal paymentsReceived,
            BigDecimal financeCharges,
            BigDecimal feesAndCharges,
            BigDecimal rewardPointsEarned,
            BigDecimal rewardPointsBalance,
            BigDecimal creditLimit,
            BigDecimal utilizationPct,
            Integer transactionCount
    ) {
    }

    public boolean isPaid() {
        return status == BillStatus.PAID;
    }
}
