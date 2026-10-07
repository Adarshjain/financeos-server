package com.financeos.api.investment.dto;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** The bank credit behind a dividend row — enough for a chip/tooltip without another round-trip. */
public record DividendTransactionSummary(
        UUID id,
        @Nullable UUID accountId,
        @Nullable String accountName,
        LocalDate date,
        @Nullable String description,
        /** Signed like {@code TransactionResponse.amount}: DEBIT negative, CREDIT positive. */
        BigDecimal signedAmount
) {
    public static DividendTransactionSummary from(Transaction t) {
        if (t == null) {
            return null;
        }
        BigDecimal signed = t.getType() == TransactionType.DEBIT ? t.getAmount().negate() : t.getAmount();
        String desc = t.getDescription() != null ? t.getDescription() : t.getSourcedDescription();
        return new DividendTransactionSummary(
                t.getId(),
                t.getAccount() != null ? t.getAccount().getId() : null,
                t.getAccount() != null ? t.getAccount().getName() : null,
                t.getDate(),
                desc,
                signed
        );
    }
}
