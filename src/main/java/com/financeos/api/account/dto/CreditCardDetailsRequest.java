package com.financeos.api.account.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record CreditCardDetailsRequest(
        @NotBlank(message = "Last 4 digits are required")
        String last4,

        @NotNull(message = "Credit limit is required")
        @DecimalMin(value = "0", message = "Credit limit must be non-negative")
        BigDecimal creditLimit,

        @NotNull(message = "Payment due day is required")
        @Min(value = 1, message = "Payment due day must be between 1 and 31")
        @Max(value = 31, message = "Payment due day must be between 1 and 31")
        Integer paymentDueDay,

        @NotNull(message = "Grace period days is required")
        @Min(value = 0, message = "Grace period must be non-negative")
        Integer gracePeriodDays,

        String statementPassword
) {}

