package com.financeos.domain.notification.bill;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One credit card's current bill: the latest live statement plus everything the settlement
 * signals say about it. Never stored — the dashboard card and the notification tick both
 * build it through {@link CardBillService}, so they cannot disagree.
 *
 * <p>A card with no live statement is still listed, as an {@link BillStatus#AWAITING_STATEMENT}
 * row: {@code statementId}, the period, the due date and every total are null; only the account
 * fields, {@code unbilledAmount} and {@code nextStatementExpectedOn} are filled.
 */
public record CardBill(
        UUID accountId,
        String accountName,
        String last4,
        @Nullable UUID statementId,
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
        Digest digest,
        /**
         * Card spend after the statement period end (since ever for a card without one): the
         * non-excluded debits only. Credits never reduce it; payments and refunds count towards the
         * open bill instead. Never negative. Null when {@link CardBillService#build} produced the row
         * directly or the statement has no period end.
         */
        @Nullable BigDecimal unbilledAmount,
        /**
         * Projected close of the cycle after the latest dated statement. May already be in the past when
         * the next statement is late; null when the card has no dated statement.
         */
        @Nullable LocalDate nextStatementExpectedOn
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

    /** The same bill with the two read-side extras filled in. */
    public CardBill withUnbilled(@Nullable BigDecimal unbilledAmount, @Nullable LocalDate nextStatementExpectedOn) {
        return new CardBill(accountId, accountName, last4, statementId, periodStart, periodEnd, paymentDueDate,
                totalAmountDue, minimumAmountDue, paidAmount, remainingAmount, paidSource, status, daysUntilDue,
                paidMarkedOn, possiblePayments, muted, statementCreatedAt, lastNotifiedKind, lastNotifiedOn, digest,
                unbilledAmount, nextStatementExpectedOn);
    }
}
