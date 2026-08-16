package com.financeos.api.cardfee.dto;

import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record FeeChargeCandidateResponse(
        UUID transactionId,
        LocalDate date,
        String description,
        String sourcedDescription,
        BigDecimal amount,
        TransactionType type,
        BigDecimal amountDelta
) {
}
