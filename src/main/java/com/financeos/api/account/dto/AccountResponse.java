package com.financeos.api.account.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.financeos.domain.account.*;
import com.financeos.domain.account.card.AccountCard;

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

    Instant createdAt();

    Instant updatedAt();

    LocalDate ingestFromDate();

    BigDecimal balance();

    Boolean balanceAnchored();

    BigDecimal reconciliationGap();

    LocalDate anchorDate();

    static AccountResponse from(Account account) {
        return from(account, null, null);
    }

    static AccountResponse from(Account account, String derivedLast4, List<AccountCardResponse> cardResponses) {
        BigDecimal bal = account.getCalculatedBalance();
        Boolean anchored = account.getBalanceAnchored() != null ? account.getBalanceAnchored() : false;
        BigDecimal gap = account.getReconciliationGap();
        LocalDate anchorDate = account.getAnchorDate();

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
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        account.getIngestFromDate(),
                        details != null ? details.getOpeningBalance() : null,
                        details != null ? details.getLast4() : null,
                        account.getLastStatementDate(),
                        bal,
                        anchored,
                        gap,
                        anchorDate);
            }
            case credit_card -> {
                AccountCreditCardDetails details = account.getCreditCardDetails();
                String last4 = derivedLast4;
                if (last4 == null && account.getCards() != null && !account.getCards().isEmpty()) {
                    last4 = account.getCards().stream()
                            .filter(c -> c.isPrimary() && c.getClosedOn() == null)
                            .map(AccountCard::getLast4)
                            .findFirst()
                            .orElseGet(() -> account.getCards().stream()
                                    .filter(c -> c.getClosedOn() == null)
                                    .map(AccountCard::getLast4)
                                    .findFirst()
                                    .orElseGet(() -> account.getCards().get(0).getLast4()));
                }
                // The account list does not count transactions per card; emitting 0 would read as
                // "no transactions, safe to delete". CardsDialog fetches real counts separately.
                List<AccountCardResponse> cards = cardResponses != null ? cardResponses :
                        (account.getCards() != null ? account.getCards().stream()
                                .map(AccountCardResponse::withoutCount)
                                .toList() : List.of());
                yield new CreditCardAccountResponse(
                        account.getId(),
                        account.getName(),
                        account.getType(),
                        account.getExcludeFromNetAsset(),
                        account.getFinancialPosition(),
                        account.getDescription(),
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        account.getIngestFromDate(),
                        last4,
                        details != null ? details.getCreditLimit() : null,
                        details != null ? details.getPaymentDueDay() : null,
                        details != null ? details.getGracePeriodDays() : null,
                        account.getRewardAnniversaryDate(),
                        account.getLastStatementDate(),
                        bal,
                        anchored,
                        gap,
                        anchorDate,
                        cards);
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
                        account.getCreatedAt(),
                        account.getUpdatedAt(),
                        account.getIngestFromDate(),
                        details != null ? details.getProvider() : null,
                        details != null ? details.getClientId() : null,
                        details != null ? details.getCashBalance() : BigDecimal.ZERO,
                        bal,
                        anchored,
                        gap,
                        anchorDate);
            }
            default -> new GenericAccountResponse(
                    account.getId(),
                    account.getName(),
                    account.getType(),
                    account.getExcludeFromNetAsset(),
                    account.getFinancialPosition(),
                    account.getDescription(),
                    account.getCreatedAt(),
                    account.getUpdatedAt(),
                    account.getIngestFromDate(),
                    bal,
                    anchored,
                    gap,
                    anchorDate);
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
            LocalDate ingestFromDate,
            BigDecimal openingBalance,
            String last4,
            LocalDate lastStatementDate,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate) implements AccountResponse {
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
            LocalDate ingestFromDate,
            String last4,
            BigDecimal creditLimit,
            Integer paymentDueDay,
            Integer gracePeriodDays,
            LocalDate anniversaryDate,
            LocalDate lastStatementDate,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate,
            List<AccountCardResponse> cards) implements AccountResponse {
    }

    record BrokerAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            Instant createdAt,
            Instant updatedAt,
            LocalDate ingestFromDate,
            String provider,
            String clientId,
            BigDecimal cashBalance,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate) implements AccountResponse {
    }

    record GenericAccountResponse(
            UUID id,
            String name,
            AccountType type,
            Boolean excludeFromNetAsset,
            FinancialPosition financialPosition,
            String description,
            Instant createdAt,
            Instant updatedAt,
            LocalDate ingestFromDate,
            BigDecimal balance,
            Boolean balanceAnchored,
            BigDecimal reconciliationGap,
            LocalDate anchorDate) implements AccountResponse {
    }
}
