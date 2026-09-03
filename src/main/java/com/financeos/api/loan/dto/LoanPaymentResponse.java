package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanPayment;

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
        Instant createdAt
) {
    public static LoanPaymentResponse from(LoanPayment payment) {
        return new LoanPaymentResponse(
                payment.getId(),
                payment.getLoan().getId(),
                payment.getInstallmentSeq(),
                payment.getPaymentDate(),
                payment.getAmount(),
                payment.getTransaction() != null ? payment.getTransaction().getId() : null,
                payment.getCreatedAt()
        );
    }
}
