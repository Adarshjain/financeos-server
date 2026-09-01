package com.financeos.api.account.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.financeos.domain.account.*;
import com.financeos.domain.account.card.Cardholder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AccountResponse.BankAccountResponse.class, name = "bank_account"),
        @JsonSubTypes.Type(value = AccountResponse.CreditCardAccountResponse.class, name = "credit_card"),
        @JsonSubTypes.Type(value = AccountResponse.BrokerAccountResponse.class, name = "broker"),
        @JsonSubTypes.Type(value = AccountResponse.GenericAccountResponse.class, name = "generic")
})
public sealed interface AccountResponse {
    UUID id();

    String name();

    AccountType type();

    Boolean excludeFromNetAsset();

    FinancialPosition financialPosition();

    String description();

    LocalDate closedOn();

    UUID replacesAccountId();

    Instant createdAt();

    Instant updatedAt();

    LocalDate ingestFromDate();

    BigDecimal balance();

    Boolean balanceAnchored();

    BigDecimal reconciliationGap();

    LocalDate anchorDate();

    List<String> warnings();

    static AccountResponse from(Account account) {
        return from(account, null, null, null);
    }

    static AccountResponse from(Account account, String derivedLast4, List<CardholderResponse> cardholderResponses) {
        return from(account, derivedLast4, cardholderResponses, null);
    }

    static AccountResponse from(Account account, String derivedLast4, List<CardholderResponse> cardholderResponses, List<String> warnings) {
        BigDecimal bal = account.getCalculatedBalance();
        Boolean anchored = account.getBalanceAnchored() != null ? account.getBalanceAnchored() : false;
        BigDecimal gap = account.getReconciliationGap();
        LocalDate anchorDate = account.getAnchorDate();
        List<String> warnList = warnings != null ? warnings : List.of();
        UUID replacesAccId = account.getReplacesAccount() != null ? account.getReplacesAccount().getId() : null;

        return switch (account.getType()) {
            case bank_account -> {
                AccountBankDetails details = account.getBankDetails();
                if (bal == null && details != null) {
                    bal = details.getOpeningBalance();
                }
                yield new BankAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getClosedOn(),
                        replacesAccId,
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        account.getIngestFromDate(),
                        details != null ? details.getOpeningBalance() : null,
                        details != null ? details.getLast4() : null,
                        account.getLastStatementDate(),
                        bal,
                        anchored,
                        gap,
                        anchorDate,
                        warnList);
            }
            case credit_card -> {
                AccountCreditCardDetails details = account.getCreditCardDetails();
                String last4 = derivedLast4 != null ? derivedLast4 : account.primaryLast4();
                List<CardholderResponse> cardholders = cardholderResponses != null ? cardholderResponses :
                        (account.getCardholders() != null ? account.getCardholders().stream()
                                .map(CardholderResponse::from)
                                .toList() : List.of());
                yield new CreditCardAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getClosedOn(),
                        replacesAccId,
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        account.getIngestFromDate(),
                        last4,
                        details != null ? details.getCreditLimit() : null,
                        details != null ? details.getPaymentDueDay() : null,
                        details != null ? details.getGracePeriodDays() : null,
                        details != null ? details.getIssuer() : null,
                        details != null ? details.getProductName() : null,
                        account.getRewardAnniversaryDate(),
                        account.getLastStatementDate(),
                        bal,
                        anchored,
                        gap,
                        anchorDate,
                        cardholders,
                        warnList);
            }
            case broker -> {
                AccountBrokerDetails details = account.getBrokerDetails();
                yield new BrokerAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getClosedOn(),
                        replacesAccId,
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        account.getIngestFromDate(),
                        details != null ? details.getProvider() : null,
                        details != null ? details.getClientId() : null,
                        details != null ? details.getCashBalance() : BigDecimal.ZERO,
                        bal,
                        anchored,
                        gap,
                        anchorDate,
                        warnList);
            }
            default -> new GenericAccountResponse(
                    account.getId(),
                    account.getName(),
                    account.getType(),
                    account.getExcludeFromNetAsset(),
                    account.getFinancialPosition(),
                    account.getDescription(),
                    account.getClosedOn(),
                    replacesAccId,
                    account.getCreatedAt(),
                    account.getUpdatedAt(),
                    account.getIngestFromDate(),
                    bal,
                    anchored,
                    gap,
                    anchorDate,
                    warnList);
        };
    }

    record BankAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            LocalDate closedOn,
            UUID replacesAccountId,
            Instant createdAt,
            Instant updatedAt,
            LocalDate ingestFromDate,
            BigDecimal openingBalance,
            String last4,
            LocalDate lastStatementDate,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate,
            List<String> warnings) implements AccountResponse {
    }

    record CreditCardAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            LocalDate closedOn,
            UUID replacesAccountId,
            Instant createdAt,
            Instant updatedAt,
            LocalDate ingestFromDate,
            String last4,
            BigDecimal creditLimit,
            Integer paymentDueDay,
            Integer gracePeriodDays,
            String issuer,
            String productName,
            LocalDate anniversaryDate,
            LocalDate lastStatementDate,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate,
            List<CardholderResponse> cardholders,
            List<String> warnings) implements AccountResponse {
    }

    record BrokerAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            LocalDate closedOn,
            UUID replacesAccountId,
            Instant createdAt,
            Instant updatedAt,
            LocalDate ingestFromDate,
            String provider,
            String clientId,
            BigDecimal cashBalance,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate,
            List<String> warnings) implements AccountResponse {
    }

    record GenericAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            LocalDate closedOn,
            UUID replacesAccountId,
            Instant createdAt,
            Instant updatedAt,
            LocalDate ingestFromDate,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate,
            List<String> warnings) implements AccountResponse {
    }
}
