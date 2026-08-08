package com.financeos.api.lending.dto;

import com.financeos.domain.lending.LendingDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateLendingRequest(
        LendingDirection direction,
        BigDecimal amount,
        LocalDate lendDate,
        LocalDate expectedReturnDate,
        String notes
) {}
