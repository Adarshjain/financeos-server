package com.financeos.api.search.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A transaction matched by description or amount; {@code amount} is signed (credit positive, debit negative). */
public record SearchTransactionHit(UUID id, LocalDate date, String description, UUID accountId, String accountName,
                                   BigDecimal amount) {
}
