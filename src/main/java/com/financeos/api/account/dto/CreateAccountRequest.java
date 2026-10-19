package com.financeos.api.account.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateAccountRequest.BankAccountRequest.class, name = "bank_account"),
        @JsonSubTypes.Type(value = CreateAccountRequest.CreditCardRequest.class, name = "credit_card"),
        @JsonSubTypes.Type(value = CreateAccountRequest.BrokerRequest.class, name = "broker"),
        @JsonSubTypes.Type(value = CreateAccountRequest.GenericAccountRequest.class, name = "generic")
})
public sealed interface CreateAccountRequest {
    @NotBlank(message = "Name is required")
    String name();

    @NotNull(message = "Account type is required")
    AccountType type();

    Boolean excludeFromNetAsset();

    FinancialPosition financialPosition();

    String description();

    LocalDate ingestFromDate();

    /** BankCatalog id; optional, bank accounts and credit cards only (null for the other types). */
    @Nullable
    String bank();

    record BankAccountRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            BigDecimal openingBalance,
            String last4,
            String statementPassword,
            LocalDate ingestFromDate,
            @Nullable String bank
    ) implements CreateAccountRequest {
        public BankAccountRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                  FinancialPosition financialPosition, String description,
                                  BigDecimal openingBalance, String last4, String statementPassword,
                                  LocalDate ingestFromDate) {
            this(name, type, excludeFromNetAsset, financialPosition, description, openingBalance, last4,
                    statementPassword, ingestFromDate, null);
        }
    }

    record CreditCardRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            @NotBlank(message = "Last 4 digits are required") String last4,
            /**
             * The account's own limit: required unless {@code limitPool} is set, where it is
             * ignored (a pooled card has no limit of its own; AccountService stores null).
             */
            @Nullable BigDecimal creditLimit,
            /** Card membership anniversary — anchors ANNIVERSARY_YEAR reward windows. */
            @NotNull(message = "Card anniversary date is required") LocalDate anniversaryDate,
            String statementPassword,
            LocalDate ingestFromDate,
            java.util.UUID replacesAccountId,
            String issuer,
            String productName,
            /** The shared limit this account draws on; null = not pooled. */
            @Nullable CreditLimitPoolRequest limitPool,
            @Nullable String bank
    ) implements CreateAccountRequest {
        /** A card without a bank, for callers that predate it. */
        public CreditCardRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                 FinancialPosition financialPosition, String description, String last4,
                                 BigDecimal creditLimit, LocalDate anniversaryDate, String statementPassword,
                                 LocalDate ingestFromDate, java.util.UUID replacesAccountId, String issuer,
                                 String productName, CreditLimitPoolRequest limitPool) {
            this(name, type, excludeFromNetAsset, financialPosition, description, last4, creditLimit,
                    anniversaryDate, statementPassword, ingestFromDate, replacesAccountId, issuer, productName,
                    limitPool, null);
        }

        /** An unpooled card, for callers that predate shared limits. */
        public CreditCardRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                 FinancialPosition financialPosition, String description, String last4,
                                 BigDecimal creditLimit, LocalDate anniversaryDate, String statementPassword,
                                 LocalDate ingestFromDate, java.util.UUID replacesAccountId, String issuer,
                                 String productName) {
            this(name, type, excludeFromNetAsset, financialPosition, description, last4, creditLimit,
                    anniversaryDate, statementPassword, ingestFromDate, replacesAccountId, issuer, productName,
                    null, null);
        }
    }

    record BrokerRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            @NotBlank(message = "Provider is required") String provider,
            String clientId,
            BigDecimal cashBalance
    ) implements CreateAccountRequest {
        /** Brokers are fed by broker imports, never by statements or Gmail: no ingest watermark. */
        @Override
        public LocalDate ingestFromDate() {
            return null;
        }

        /** No bank on brokers: a bank sent for one is ignored. */
        @Override
        public String bank() {
            return null;
        }
    }

    record GenericAccountRequest(
            @NotBlank(message = "Name is required") String name,
            @NotNull(message = "Account type is required") AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description
    ) implements CreateAccountRequest {
        /** Wallet/Cash accounts are manual-only: no statements, no Gmail sync, no ingest watermark. */
        @Override
        public LocalDate ingestFromDate() {
            return null;
        }

        /** No bank on generic accounts: a bank sent for it is ignored. */
        @Override
        public String bank() {
            return null;
        }
    }
}
