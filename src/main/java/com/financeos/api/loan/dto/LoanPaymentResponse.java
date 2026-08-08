package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanPayment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanPaymentResponse(
        UUID id,
        UUID loanId,
        Integer installmentSeq,
        LocalDate paymentDate,
        BigDecimal amount,
        UUID transactionId,
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
