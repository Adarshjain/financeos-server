package com.financeos.api.obligations.dto;

import com.financeos.domain.lending.LendingDirection;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ObligationItemDto(
        String type, // "emi" | "lending_due"
        LocalDate date,
        BigDecimal amount,
        String status, // "upcoming" | "overdue"
        @Nullable UUID loanId,
        @Nullable String loanName,
        @Nullable Integer installmentSeq,
        @Nullable UUID lendingId,
        @Nullable UUID counterpartyId,
        @Nullable String counterpartyName,
        @Nullable LendingDirection direction
) {}
