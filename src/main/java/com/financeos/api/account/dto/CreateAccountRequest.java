package com.financeos.api.account.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.account.card.CardholderRelationship;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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

    /** The account suggestion this account is created from; null = none (always null for brokers and generic accounts). */
    @Nullable
    default java.util.UUID fromSuggestionId() {
        return null;
    }

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
            @Nullable String bank,
            /** The one open debit card of the account's PRIMARY/SELF holder; null = none. */
            @Nullable @Valid DebitCardRequest debitCard,
            /** Earlier (closed) numbers of the debit card; null/empty = none. */
            @Nullable List<@Valid EarlierCardRequest> earlierCards,
            /** The suggestion this account is added from; it becomes ADDED with the account and card. */
            @Nullable java.util.UUID fromSuggestionId
    ) implements CreateAccountRequest {
        /** Without a suggestion, for callers that predate it. */
        public BankAccountRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                  FinancialPosition financialPosition, String description,
                                  BigDecimal openingBalance, String last4, String statementPassword,
                                  LocalDate ingestFromDate, @Nullable String bank,
                                  @Nullable DebitCardRequest debitCard, @Nullable List<EarlierCardRequest> earlierCards) {
            this(name, type, excludeFromNetAsset, financialPosition, description, openingBalance, last4,
                    statementPassword, ingestFromDate, bank, debitCard, earlierCards, null);
        }

        public BankAccountRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                  FinancialPosition financialPosition, String description,
                                  BigDecimal openingBalance, String last4, String statementPassword,
                                  LocalDate ingestFromDate, @Nullable String bank) {
            this(name, type, excludeFromNetAsset, financialPosition, description, openingBalance, last4,
                    statementPassword, ingestFromDate, bank, null, null, null);
        }

        public BankAccountRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                  FinancialPosition financialPosition, String description,
                                  BigDecimal openingBalance, String last4, String statementPassword,
                                  LocalDate ingestFromDate) {
            this(name, type, excludeFromNetAsset, financialPosition, description, openingBalance, last4,
                    statementPassword, ingestFromDate, null, null, null, null);
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
            @Nullable String bank,
            /** Add-on cards saved with the account, each on its own ADDON holder; null/empty = none. */
            @Nullable List<@Valid AddonCardRequest> addonCards,
            /** Earlier (closed) numbers of the primary card; null/empty = none. */
            @Nullable List<@Valid EarlierCardRequest> earlierCards,
            /** The suggestion this card account is added from; it becomes ADDED with the account and card. */
            @Nullable java.util.UUID fromSuggestionId,
            /** Optional last 6 digits of the card (must end with last4); null keeps the stored value on update. */
            @Nullable String last6
    ) implements CreateAccountRequest {
        /** Without a last 6, for callers that predate it. */
        public CreditCardRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                 FinancialPosition financialPosition, String description, String last4,
                                 BigDecimal creditLimit, LocalDate anniversaryDate, String statementPassword,
                                 LocalDate ingestFromDate, java.util.UUID replacesAccountId, String issuer,
                                 String productName, CreditLimitPoolRequest limitPool, String bank,
                                 List<AddonCardRequest> addonCards, List<EarlierCardRequest> earlierCards,
                                 java.util.UUID fromSuggestionId) {
            this(name, type, excludeFromNetAsset, financialPosition, description, last4, creditLimit,
                    anniversaryDate, statementPassword, ingestFromDate, replacesAccountId, issuer, productName,
                    limitPool, bank, addonCards, earlierCards, fromSuggestionId, null);
        }

        /** Without a suggestion, for callers that predate it. */
        public CreditCardRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                 FinancialPosition financialPosition, String description, String last4,
                                 BigDecimal creditLimit, LocalDate anniversaryDate, String statementPassword,
                                 LocalDate ingestFromDate, java.util.UUID replacesAccountId, String issuer,
                                 String productName, CreditLimitPoolRequest limitPool, String bank,
                                 List<AddonCardRequest> addonCards, List<EarlierCardRequest> earlierCards) {
            this(name, type, excludeFromNetAsset, financialPosition, description, last4, creditLimit,
                    anniversaryDate, statementPassword, ingestFromDate, replacesAccountId, issuer, productName,
                    limitPool, bank, addonCards, earlierCards, null);
        }

        /** Without add-on or earlier cards, for callers that predate them. */
        public CreditCardRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                 FinancialPosition financialPosition, String description, String last4,
                                 BigDecimal creditLimit, LocalDate anniversaryDate, String statementPassword,
                                 LocalDate ingestFromDate, java.util.UUID replacesAccountId, String issuer,
                                 String productName, CreditLimitPoolRequest limitPool, String bank) {
            this(name, type, excludeFromNetAsset, financialPosition, description, last4, creditLimit,
                    anniversaryDate, statementPassword, ingestFromDate, replacesAccountId, issuer, productName,
                    limitPool, bank, null, null, null);
        }

        /** A card without a bank, for callers that predate it. */
        public CreditCardRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                 FinancialPosition financialPosition, String description, String last4,
                                 BigDecimal creditLimit, LocalDate anniversaryDate, String statementPassword,
                                 LocalDate ingestFromDate, java.util.UUID replacesAccountId, String issuer,
                                 String productName, CreditLimitPoolRequest limitPool) {
            this(name, type, excludeFromNetAsset, financialPosition, description, last4, creditLimit,
                    anniversaryDate, statementPassword, ingestFromDate, replacesAccountId, issuer, productName,
                    limitPool, null, null, null, null);
        }

        /** An unpooled card, for callers that predate shared limits. */
        public CreditCardRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                 FinancialPosition financialPosition, String description, String last4,
                                 BigDecimal creditLimit, LocalDate anniversaryDate, String statementPassword,
                                 LocalDate ingestFromDate, java.util.UUID replacesAccountId, String issuer,
                                 String productName) {
            this(name, type, excludeFromNetAsset, financialPosition, description, last4, creditLimit,
                    anniversaryDate, statementPassword, ingestFromDate, replacesAccountId, issuer, productName,
                    null, null, null, null, null);
        }
    }

    /** An add-on card created with the account: its own ADDON holder plus one card. */
    record AddonCardRequest(
            @NotBlank(message = "Add-on last 4 digits are required")
            @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
            String last4,
            @NotNull(message = "Add-on relationship is required") CardholderRelationship relationship,
            @Nullable String holderName
    ) {}

    /** An earlier number of the primary card, saved closed so its old mail and statements land here. */
    record EarlierCardRequest(
            @NotBlank(message = "Earlier card last 4 digits are required")
            @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
            String last4,
            /** Defaults to today (AppTime) when absent. */
            @Nullable LocalDate closedOn
    ) {}

    /** A debit card of a bank account's primary holder. */
    record DebitCardRequest(
            @NotBlank(message = "Debit card last 4 digits are required")
            @Pattern(regexp = "^[0-9]{4}$", message = "last4 must be exactly 4 digits")
            String last4
    ) {}

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
            String description,
            /**
             * Starting balance as sent: absent (null here) keeps the stored value on update, an explicit JSON null
             * clears it, a number sets it. Read it through {@link #hasOpeningBalance()} and {@link #openingBalanceValue()}.
             */
            @Nullable com.fasterxml.jackson.databind.JsonNode openingBalance
    ) implements CreateAccountRequest {
        /** Without an opening balance (left as it is), for callers that predate it. */
        public GenericAccountRequest(String name, AccountType type, Boolean excludeFromNetAsset,
                                     FinancialPosition financialPosition, String description) {
            this(name, type, excludeFromNetAsset, financialPosition, description, null);
        }

        /** The request says something about the opening balance (a number or an explicit null). */
        public boolean hasOpeningBalance() {
            return openingBalance != null;
        }

        /** The opening balance sent; null when it was cleared (or never sent). */
        @Nullable
        public BigDecimal openingBalanceValue() {
            if (openingBalance == null || openingBalance.isNull() || openingBalance.isMissingNode()) {
                return null;
            }
            if (!openingBalance.isNumber()) {
                throw new com.financeos.core.exception.ValidationException("Opening balance must be a number");
            }
            return openingBalance.decimalValue();
        }

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
