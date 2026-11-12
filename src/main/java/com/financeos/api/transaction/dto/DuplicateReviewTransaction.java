package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.TransactionSource;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One side of a suspected duplicate, with the facts the user compares. */
public record DuplicateReviewTransaction(
        UUID id,
        LocalDate date,
        @Nullable BigDecimal amount,
        @Nullable String description,
        @Nullable String accountName,
        @Nullable TransactionSource source
) {}
