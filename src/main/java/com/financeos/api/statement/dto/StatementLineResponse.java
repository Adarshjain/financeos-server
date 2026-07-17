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
) {
    public static StatementLineResponse from(com.financeos.domain.statement.StatementLineProjection p) {
        if (p == null) return null;
        return new StatementLineResponse(
                p.transactionId(),
                p.lineIndex(),
                p.date(),
                p.description(),
                p.amount(),
                p.type(),
                p.reviewType(),
                p.balanceAfter(),
                p.chainValid()
        );
    }
}
