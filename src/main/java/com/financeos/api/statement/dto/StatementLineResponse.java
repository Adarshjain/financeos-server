package com.financeos.api.statement.dto;

import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record StatementLineResponse(
        UUID transactionId,
        Integer lineIndex,
        LocalDate date,
        String description,
        BigDecimal amount,
        TransactionType type,
        ReviewType reviewType,
        BigDecimal balanceAfter,
        Boolean chainValid
) {}
