package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A row of one Sort-them group: what the list row shows (account and last 4, how it arrived), signed amount. */
public record UncategorizedTransactionResponse(
        UUID id,
        LocalDate date,
        @Nullable String description,
        @Nullable String sourcedDescription,
        UUID accountId,
        String accountName,
        /** Last 4 of the account's number, else of the card the spend was on. */
        @Nullable String accountLast4,
        @Nullable String cardLabel,
        @Nullable String cardLast4,
        TransactionSource source,
        @Nullable TransactionChannel channel,
        /** Negative for money out. */
        BigDecimal amount,
        TransactionType type) {
}
