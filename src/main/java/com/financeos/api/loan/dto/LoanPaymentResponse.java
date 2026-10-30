package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanPayment;
import com.financeos.domain.transaction.link.LinkOrigin;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanPaymentResponse(
        UUID id,
        UUID loanId,
        @Nullable Integer installmentSeq,
        LocalDate paymentDate,
        BigDecimal amount,
        @Nullable UUID transactionId,
        Instant createdAt,
        /** USER, or AUTO when the payment was matched automatically. */
        LinkOrigin origin
) {
    public static LoanPaymentResponse from(LoanPayment payment) {
        return new LoanPaymentResponse(
                payment.getId(),
                payment.getLoan().getId(),
                payment.getInstallmentSeq(),
                payment.getPaymentDate(),
                payment.getAmount(),
                payment.getTransaction() != null ? payment.getTransaction().getId() : null,
                payment.getCreatedAt(),
                payment.getOrigin() != null ? payment.getOrigin() : LinkOrigin.USER
        );
    }
}
