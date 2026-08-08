package com.financeos.api.obligations.dto;

import com.financeos.domain.lending.LendingDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ObligationItemDto(
        String type, // "emi" | "lending_due"
        LocalDate date,
        BigDecimal amount,
        String status, // "upcoming" | "overdue"
        UUID loanId,
        String loanName,
        Integer installmentSeq,
        UUID lendingId,
        UUID counterpartyId,
        String counterpartyName,
        LendingDirection direction
) {}
