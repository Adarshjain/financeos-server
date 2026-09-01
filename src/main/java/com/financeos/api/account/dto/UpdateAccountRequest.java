package com.financeos.api.account.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = UpdateAccountRequest.BankAccountRequest.class, name = "bank_account"),
        @JsonSubTypes.Type(value = UpdateAccountRequest.CreditCardRequest.class, name = "credit_card"),
        @JsonSubTypes.Type(value = UpdateAccountRequest.BrokerRequest.class, name = "broker"),
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

    LocalDate ingestFromDate();

    record BankAccountRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            BigDecimal openingBalance,
            String last4,
            String statementPassword,
            LocalDate ingestFromDate
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
            String statementPassword,
            LocalDate ingestFromDate
    ) implements UpdateAccountRequest {
    }

    record BrokerRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            @NotBlank(message = "Provider is required") String provider,
            String clientId,
            BigDecimal cashBalance,
            LocalDate ingestFromDate
    ) implements UpdateAccountRequest {
    }

    record GenericAccountRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            LocalDate ingestFromDate
    ) implements UpdateAccountRequest {
    }
}
