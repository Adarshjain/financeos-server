package com.financeos.api.loan.dto;

import com.financeos.domain.loan.LoanCharge;
import com.financeos.domain.loan.LoanChargeType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanChargeResponse(
        UUID id,
        UUID loanId,
        LoanChargeType chargeType,
        BigDecimal amount,
        LocalDate chargeDate,
        UUID transactionId,
        String notes,
        Instant createdAt
) {
    public static LoanChargeResponse from(LoanCharge charge) {
        return new LoanChargeResponse(
                charge.getId(),
                charge.getLoan().getId(),
                charge.getChargeType(),
                charge.getAmount(),
                charge.getChargeDate(),
                charge.getTransaction() != null ? charge.getTransaction().getId() : null,
                charge.getNotes(),
                charge.getCreatedAt()
        );
    }
}
