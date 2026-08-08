package com.financeos.api.loan.dto;

import com.financeos.domain.loan.AdjustmentMode;
import com.financeos.domain.loan.LoanEvent;
import com.financeos.domain.loan.LoanEventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanEventResponse(
        UUID id,
        UUID loanId,
        LoanEventType eventType,
        LocalDate effectiveDate,
        BigDecimal newAnnualRatePct,
        BigDecimal amount,
        AdjustmentMode adjustmentMode,
        BigDecimal newEmiOverride,
        UUID transactionId,
        Instant createdAt
) {
    public static LoanEventResponse from(LoanEvent event) {
        return new LoanEventResponse(
                event.getId(),
                event.getLoan().getId(),
                event.getEventType(),
                event.getEffectiveDate(),
                event.getNewAnnualRatePct(),
                event.getAmount(),
                event.getAdjustmentMode(),
                event.getNewEmiOverride(),
                event.getTransaction() != null ? event.getTransaction().getId() : null,
                event.getCreatedAt()
        );
    }
}
