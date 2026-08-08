package com.financeos.api.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InstallmentDto(
        Integer seq,
        LocalDate dueDate,
        BigDecimal openingBalance,
        BigDecimal emi,
        BigDecimal interest,
        BigDecimal principal,
        BigDecimal closingBalance,
        String status, // settled | overdue | upcoming
        PaymentInfo payment
) {
    public record PaymentInfo(
            UUID id,
            LocalDate paymentDate,
            BigDecimal amount,
            UUID transactionId
    ) {}
}
