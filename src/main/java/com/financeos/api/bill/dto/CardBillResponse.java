package com.financeos.api.bill.dto;

import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.PaidSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

public record CardBillResponse(
        UUID accountId,
        String accountName,
        @Nullable String last4,
        UUID statementId,
        @Nullable LocalDate periodStart,
        @Nullable LocalDate periodEnd,
        @Nullable LocalDate paymentDueDate,
        @Nullable BigDecimal totalAmountDue,
        @Nullable BigDecimal minimumAmountDue,
        @Nullable BigDecimal paidAmount,
        @Nullable BigDecimal remainingAmount,
        PaidSource paidSource,
        BillStatus status,
        @Nullable Long daysUntilDue,
        @Nullable LocalDate paidMarkedOn,
        List<PossiblePaymentResponse> possiblePayments,
        boolean muted,
        @Nullable Instant statementCreatedAt,
        @Nullable String lastNotifiedKind,
        @Nullable LocalDate lastNotifiedOn,
        @Nullable BillDigestResponse digest
) {
    public record PossiblePaymentResponse(UUID transactionId, @Nullable LocalDate date, @Nullable BigDecimal amount,
                                          @Nullable String description) {
    }

    public record BillDigestResponse(
            @Nullable BigDecimal totalPurchases,
            @Nullable BigDecimal paymentsReceived,
            @Nullable BigDecimal financeCharges,
            @Nullable BigDecimal feesAndCharges,
            @Nullable BigDecimal rewardPointsEarned,
            @Nullable BigDecimal rewardPointsBalance,
            @Nullable BigDecimal creditLimit,
            @Nullable BigDecimal utilizationPct,
            @Nullable Integer transactionCount) {
    }

    public static CardBillResponse from(CardBill bill) {
        CardBill.Digest d = bill.digest();
        return new CardBillResponse(
                bill.accountId(), bill.accountName(), bill.last4(), bill.statementId(),
                bill.periodStart(), bill.periodEnd(), bill.paymentDueDate(),
                bill.totalAmountDue(), bill.minimumAmountDue(), bill.paidAmount(), bill.remainingAmount(),
                bill.paidSource(), bill.status(), bill.daysUntilDue(), bill.paidMarkedOn(),
                bill.possiblePayments().stream()
                        .map(p -> new PossiblePaymentResponse(p.transactionId(), p.date(), p.amount(), p.description()))
                        .toList(),
                bill.muted(), bill.statementCreatedAt(), bill.lastNotifiedKind(), bill.lastNotifiedOn(),
                d == null ? null : new BillDigestResponse(d.totalPurchases(), d.paymentsReceived(), d.financeCharges(),
                        d.feesAndCharges(), d.rewardPointsEarned(), d.rewardPointsBalance(), d.creditLimit(),
                        d.utilizationPct(), d.transactionCount()));
    }
}
