package com.financeos.api.account.dto;

import com.financeos.domain.account.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        AccountType type,
        Boolean excludeFromNetAsset,
        FinancialPosition financialPosition,
        String description,
        BankDetailsResponse bankDetails,
        CreditCardDetailsResponse creditCardDetails,
        StockDetailsResponse stockDetails,
        MutualFundDetailsResponse mutualFundDetails,
        Instant createdAt,
        Instant updatedAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getExcludeFromNetAsset(),
                account.getFinancialPosition(),
                account.getDescription(),
                account.getBankDetails() != null ? BankDetailsResponse.from(account.getBankDetails()) : null,
                account.getCreditCardDetails() != null ? CreditCardDetailsResponse.from(account.getCreditCardDetails()) : null,
                account.getStockDetails() != null ? StockDetailsResponse.from(account.getStockDetails()) : null,
                account.getMutualFundDetails() != null ? MutualFundDetailsResponse.from(account.getMutualFundDetails()) : null,
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }

    public record BankDetailsResponse(BigDecimal openingBalance, String last4) {
        public static BankDetailsResponse from(AccountBankDetails details) {
            return new BankDetailsResponse(details.getOpeningBalance(), details.getLast4());
        }
    }

    public record CreditCardDetailsResponse(
            String last4,
            BigDecimal creditLimit,
            Integer paymentDueDay,
            Integer gracePeriodDays
    ) {
        public static CreditCardDetailsResponse from(AccountCreditCardDetails details) {
            return new CreditCardDetailsResponse(
                    details.getLast4(),
                    details.getCreditLimit(),
                    details.getPaymentDueDay(),
                    details.getGracePeriodDays()
            );
        }
    }

    public record StockDetailsResponse(String instrumentCode, BigDecimal lastTradedPrice) {
        public static StockDetailsResponse from(AccountStockDetails details) {
            return new StockDetailsResponse(details.getInstrumentCode(), details.getLastTradedPrice());
        }
    }

    public record MutualFundDetailsResponse(String instrumentCode, BigDecimal lastTradedPrice) {
        public static MutualFundDetailsResponse from(AccountMutualFundDetails details) {
            return new MutualFundDetailsResponse(details.getInstrumentCode(), details.getLastTradedPrice());
        }
    }
}

