package com.financeos.api.account.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.financeos.domain.account.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AccountResponse.BankAccountResponse.class, name = "bank_account"),
        @JsonSubTypes.Type(value = AccountResponse.CreditCardAccountResponse.class, name = "credit_card"),
        @JsonSubTypes.Type(value = AccountResponse.StockAccountResponse.class, name = "stock"),
        @JsonSubTypes.Type(value = AccountResponse.MutualFundAccountResponse.class, name = "mutual_fund"),
        @JsonSubTypes.Type(value = AccountResponse.GenericAccountResponse.class, name = "generic")
})
public sealed interface AccountResponse {
    UUID id();

    String name();

    AccountType type();

    Boolean excludeFromNetAsset();

    FinancialPosition financialPosition();

    String description();

    Instant createdAt();

    Instant updatedAt();

    static AccountResponse from(Account account) {
        return switch (account.getType()) {
            case bank_account -> {
                AccountBankDetails details = account.getBankDetails();
                yield new BankAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        details != null ? details.getOpeningBalance() : null,
                        details != null ? details.getLast4() : null);
            }
            case credit_card -> {
                AccountCreditCardDetails details = account.getCreditCardDetails();
                yield new CreditCardAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        details != null ? details.getLast4() : null,
                        details != null ? details.getCreditLimit() : null,
                        details != null ? details.getPaymentDueDay() : null,
                        details != null ? details.getGracePeriodDays() : null);
            }
            case stock -> {
                AccountStockDetails details = account.getStockDetails();
                yield new StockAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        details != null ? details.getInstrumentCode() : null,
                        details != null ? details.getLastTradedPrice() : null);
            }
            case mutual_fund -> {
                AccountMutualFundDetails details = account.getMutualFundDetails();
                yield new MutualFundAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        details != null ? details.getInstrumentCode() : null,
                        details != null ? details.getLastTradedPrice() : null);
            }
            default -> new GenericAccountResponse(
                    account.getId(),
                    account.getName(),
                    account.getType(),
                    account.getExcludeFromNetAsset(),
                    account.getFinancialPosition(),
                    account.getDescription(),
                    account.getCreatedAt(),
                    account.getUpdatedAt());
        };
    }

    record BankAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            Instant createdAt,
            Instant updatedAt,
            BigDecimal openingBalance,
            String last4) implements AccountResponse {
    }

    record CreditCardAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            Instant createdAt,
            Instant updatedAt,
            String last4,
            BigDecimal creditLimit,
            Integer paymentDueDay,
            Integer gracePeriodDays) implements AccountResponse {
    }

    record StockAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            Instant createdAt,
            Instant updatedAt,
            String instrumentCode,
            BigDecimal lastTradedPrice) implements AccountResponse {
    }

    record MutualFundAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            Instant createdAt,
            Instant updatedAt,
            String instrumentCode,
            BigDecimal lastTradedPrice) implements AccountResponse {
    }

    record GenericAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            Instant createdAt,
            Instant updatedAt) implements AccountResponse {
    }
}
