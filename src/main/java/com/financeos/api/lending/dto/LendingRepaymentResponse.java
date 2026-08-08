package com.financeos.api.lending.dto;

import com.financeos.domain.lending.LendingRepayment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LendingRepaymentResponse(
        UUID id,
        UUID lendingId,
        BigDecimal amount,
        LocalDate date,
        UUID transactionId,
        Instant createdAt
) {
    public static LendingRepaymentResponse from(LendingRepayment repayment) {
        return new LendingRepaymentResponse(
                repayment.getId(),
                repayment.getLending().getId(),
                repayment.getAmount(),
                repayment.getDate(),
                repayment.getTransaction() != null ? repayment.getTransaction().getId() : null,
                repayment.getCreatedAt()
        );
    }
}
