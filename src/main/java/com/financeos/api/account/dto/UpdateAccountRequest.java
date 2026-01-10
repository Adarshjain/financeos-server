package com.financeos.api.account.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = UpdateAccountRequest.BankAccountRequest.class, name = "bank_account"),
        @JsonSubTypes.Type(value = UpdateAccountRequest.CreditCardRequest.class, name = "credit_card"),
        @JsonSubTypes.Type(value = UpdateAccountRequest.StockRequest.class, name = "stock"),
        @JsonSubTypes.Type(value = UpdateAccountRequest.MutualFundRequest.class, name = "mutual_fund"),
        @JsonSubTypes.Type(value = UpdateAccountRequest.GenericAccountRequest.class, name = "generic")
})
public sealed interface UpdateAccountRequest {
    @NotBlank(message = "Name is required")
    String name();

    @NotNull(message = "Account type is required")
    AccountType type();

    Boolean excludeFromNetAsset();

    FinancialPosition financialPosition();

    String description();

    record BankAccountRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            BigDecimal openingBalance,
            String last4
    ) implements UpdateAccountRequest {
    }

    record CreditCardRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            @NotBlank(message = "Last 4 digits are required") String last4,
            @NotNull(message = "Credit limit is required") BigDecimal creditLimit,
            @NotNull(message = "Payment due day is required") Integer paymentDueDay,
            @NotNull(message = "Grace period days is required") Integer gracePeriodDays,
            String statementPassword
    ) implements UpdateAccountRequest {
    }

    record StockRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            @NotBlank(message = "Instrument code is required") String instrumentCode,
            BigDecimal lastTradedPrice
    ) implements UpdateAccountRequest {
    }

    record MutualFundRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            @NotBlank(message = "Instrument code is required") String instrumentCode,
            BigDecimal lastTradedPrice
    ) implements UpdateAccountRequest {
    }

    record GenericAccountRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description
    ) implements UpdateAccountRequest {
    }
}
