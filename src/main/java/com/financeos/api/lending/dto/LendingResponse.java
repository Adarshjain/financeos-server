package com.financeos.api.lending.dto;

import com.financeos.domain.lending.Lending;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LendingResponse(
        UUID id,
        UUID counterpartyId,
        String counterpartyName,
        LendingDirection direction,
        BigDecimal amount,
        LocalDate lendDate,
        LocalDate expectedReturnDate,
        LendingStatus status,
        UUID transactionId,
        String notes,
        BigDecimal repaidTotal,
        BigDecimal outstanding,
        List<LendingRepaymentResponse> repayments,
        Instant createdAt,
        Instant updatedAt
) {
    public static LendingResponse from(Lending lending, BigDecimal repaidTotal, List<LendingRepaymentResponse> repayments) {
        BigDecimal repaid = repaidTotal != null ? repaidTotal : BigDecimal.ZERO;
        BigDecimal outstanding = lending.getAmount().subtract(repaid).max(BigDecimal.ZERO);
        if (lending.getStatus() == LendingStatus.settled) {
            outstanding = BigDecimal.ZERO;
        }

        return new LendingResponse(
                lending.getId(),
                lending.getCounterparty().getId(),
                lending.getCounterparty().getName(),
                lending.getDirection(),
                lending.getAmount(),
                lending.getLendDate(),
                lending.getExpectedReturnDate(),
                lending.getStatus(),
                lending.getTransaction() != null ? lending.getTransaction().getId() : null,
                lending.getNotes(),
                repaid,
                outstanding,
                repayments != null ? repayments : List.of(),
                lending.getCreatedAt(),
                lending.getUpdatedAt()
        );
    }
}
