package com.financeos.api.bill.dto;

import com.financeos.domain.notification.bill.BillPaymentOrigin;
import com.financeos.domain.notification.bill.BillPaymentService;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.PaidSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * A card's bill. An {@code AWAITING_STATEMENT} row (open card, no live statement) has a null
 * {@code statementId}, period, due date and totals; {@code unbilledAmount} is the spend so far.
 */
public record CardBillResponse(
        UUID accountId,
        String accountName,
        @Nullable String last4,
        @Nullable UUID statementId,
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
        boolean muted,
        @Nullable Instant statementCreatedAt,
        @Nullable String lastNotifiedKind,
        @Nullable LocalDate lastNotifiedOn,
        @Nullable BillDigestResponse digest,
        @Nullable BigDecimal unbilledAmount,
        @Nullable LocalDate nextStatementExpectedOn,
        /** Statement figures still empty: PERIOD_END, DUE_DATE, TOTAL_DUE, MINIMUM_DUE; empty when complete. */
        List<String> missingDetails,
        /** Payments recorded for this bill (by the matcher or "Mark paid"), oldest first; empty when none. */
        List<PaymentResponse> payments
) {
    /** One payment of the bill: the card-side credit, the bank debit it came from (when known) and who recorded it. */
    public record PaymentResponse(UUID transactionId, @Nullable UUID bankTransactionId, @Nullable String bankAccountName,
                                  LocalDate date, BigDecimal amount, BillPaymentOrigin origin) {
        public static PaymentResponse from(BillPaymentService.PaymentView v) {
            return new PaymentResponse(v.transactionId(), v.bankTransactionId(), v.bankAccountName(), v.date(), v.amount(), v.origin());
        }
    }

    public record BillDigestResponse(
            @Nullable BigDecimal totalPurchases,
            @Nullable BigDecimal paymentsReceived,
            @Nullable BigDecimal financeCharges,
            @Nullable BigDecimal feesAndCharges,
            @Nullable BigDecimal rewardPointsEarned,
            @Nullable BigDecimal rewardPointsBalance,
            /** The card's own limit, else the statement's. */
            @Nullable BigDecimal creditLimit,
            /** Live: what the card owes now ÷ limit × 100, one decimal (same as GET /accounts); null without a limit. */
            @Nullable BigDecimal utilizationPct,
            @Nullable Integer transactionCount) {
    }

    public static CardBillResponse from(CardBill bill) {
        return from(bill, List.of());
    }

    public static CardBillResponse from(CardBill bill, List<BillPaymentService.PaymentView> payments) {
        CardBill.Digest d = bill.digest();
        return new CardBillResponse(
                bill.accountId(), bill.accountName(), bill.last4(), bill.statementId(),
                bill.periodStart(), bill.periodEnd(), bill.paymentDueDate(),
                bill.totalAmountDue(), bill.minimumAmountDue(), bill.paidAmount(), bill.remainingAmount(),
                bill.paidSource(), bill.status(), bill.daysUntilDue(), bill.paidMarkedOn(),
                bill.muted(), bill.statementCreatedAt(), bill.lastNotifiedKind(), bill.lastNotifiedOn(),
                d == null ? null : new BillDigestResponse(d.totalPurchases(), d.paymentsReceived(), d.financeCharges(),
                        d.feesAndCharges(), d.rewardPointsEarned(), d.rewardPointsBalance(), d.creditLimit(),
                        d.utilizationPct(), d.transactionCount()),
                bill.unbilledAmount(), bill.nextStatementExpectedOn(), bill.missingDetails(),
                payments.stream().map(PaymentResponse::from).toList());
    }
}
