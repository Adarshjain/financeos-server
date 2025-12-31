package com.financeos.api.account.dto;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(
        @NotBlank(message = "Name is required")
        String name,

        @NotNull(message = "Account type is required")
        AccountType type,

        Boolean excludeFromNetAsset,

        FinancialPosition financialPosition,

        String description
) {}

