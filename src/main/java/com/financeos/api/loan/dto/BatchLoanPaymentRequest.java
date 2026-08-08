package com.financeos.api.loan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BatchLoanPaymentRequest(
        @NotEmpty @Size(min = 1, max = 500) List<@Valid BatchLoanPaymentItem> items
) {}
