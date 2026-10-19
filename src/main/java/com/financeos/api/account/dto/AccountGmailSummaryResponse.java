package com.financeos.api.account.dto;

import com.financeos.domain.transaction.TransactionType;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** The account's Gmail block: its latest alert-created transaction and its open attention items. */
public record AccountGmailSummaryResponse(@Nullable LastAlert lastAlert, long unmatchedCount) {

    public record LastAlert(UUID transactionId, LocalDate date, String description, BigDecimal amount,
                            TransactionType type) {
    }
}
