package com.financeos.api.account.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreditCardDetailsRequest(
        @NotBlank(message = "Last 4 digits are required")
        String last4,

        @NotNull(message = "Credit limit is required")
        @DecimalMin(value = "0", message = "Credit limit must be non-negative")
        BigDecimal creditLimit,

        String statementPassword
) {}

